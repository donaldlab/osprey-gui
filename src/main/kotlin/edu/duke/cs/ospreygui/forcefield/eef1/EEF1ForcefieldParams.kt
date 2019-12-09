package edu.duke.cs.ospreygui.forcefield.eef1

import cuchaz.kludge.tools.sqrt
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.AtomMap
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import java.util.*
import kotlin.math.PI
import kotlin.math.exp


class EEF1ForcefieldParams : ForcefieldParams {

	companion object {

		val trigConst = 2.0/(4.0*PI*PI.sqrt())

		/**
		 * Solvation interactions for atoms more than 9 A apart are already counted in dGref.
		 */
		const val cutoff = 9.0
	}

	override val forcefield = Forcefield.EEF1

	/**
	 * Scaling to apply to the solvent forcefield energy.
	 *
	 * The default value of 0.5 was determined empirically by Osprey developers
	 * to achieve good balance between the Amber96 and EEF1 forcefields.
	 */
	var scale = 0.5


	class AtomParams(
		atom: Atom,
		val type: EEF1.AtomType
	) : ForcefieldParams.AtomParams(atom) {


		override fun hashCode() =
			type.hashCode()

		override fun equals(other: Any?) =
			other is AtomParams
				&& this.type == other.type

		override fun toString() =
			"type=$type"
	}

	class MolParams(
		mol: Molecule
	) : ForcefieldParams.MolParams(mol) {

		val atomsParams = IdentityHashMap<Atom,AtomParams>()

		override fun get(atom: Atom) =
			atomsParams[atom]
	}

	override fun parameterize(mol: Molecule, netCharge: Int?) =
		MolParams(mol).apply {
			for (atom in mol.atoms) {
				val type = atom.atomTypeEEF1(mol) ?: continue
				atomsParams[atom] = AtomParams(atom, type)
			}
		}


	override fun internalEnergy(molParams: ForcefieldParams.MolParams, atom: Atom): Double? {

		molParams as MolParams

		val type = molParams.atomsParams[atom]?.type ?: return null
		return scale*type.dGref
	}

	inner class PairParams(
		val vdwRadiusa: Double,
		val lambdaa: Double,
		val vdwRadiusb: Double,
		val lambdab: Double,
		val alpha1: Double,
		val alpha2: Double
	) : ForcefieldParams.ParamsList {

		constructor (a: EEF1.AtomType, b: EEF1.AtomType) : this(
			a.vdwRadius,
			a.lambda,
			b.vdwRadius,
			b.lambda,
			scale*trigConst*a.dGfree*b.volume/a.lambda,
			scale*trigConst*b.dGfree*a.volume/b.lambda
		)

		override val list = listOf(
			vdwRadiusa,
			lambdaa,
			vdwRadiusb,
			lambdab,
			alpha1,
			alpha2
		)

		fun calcEnergy(r: Double): Double {
			return if (r <= cutoff) {
				val Xij = (r - vdwRadiusa)/lambdaa
				val Xji = (r - vdwRadiusb)/lambdab
				val r2 = r*r
				-(alpha1*exp(-Xij*Xij) + alpha2*exp(-Xji*Xji))/r2
			} else {
				0.0
			}
		}
	}

	override fun pairParams(molaParams: ForcefieldParams.MolParams, atoma: Atom, molbParams: ForcefieldParams.MolParams, atomb: Atom, dist: Int?): PairParams? {

		// EEF1 only has interactions between atoms at least 1-4 bonded (ie >= 3 bonds away)
		// so skip the params if we're less than 3 bonds away
		if (dist != null && dist < 3) {
			return null
		}

		molaParams as MolParams
		molbParams as MolParams

		val atype = molaParams.atomsParams[atoma]?.type ?: return null
		val btype = molbParams.atomsParams[atomb]?.type ?: return null
		return PairParams(atype, btype)
	}

	override fun calcEnergy(atomsByMols: Map<Molecule,List<Atom>>, molParamsByMols: Map<Molecule,ForcefieldParams.MolParams>, molToParams: Map<Molecule,AtomMap>?): Double {

		var energy = 0.0

		// add the internal energies
		for ((mol, atoms) in atomsByMols) {
			val molParams = molParamsByMols.getValue(mol)
			for (atom in atoms) {
				val patom = molToParams?.getValue(mol)?.getBOrThrow(atom) ?: atom
				val internalEnergy = internalEnergy(molParams, patom) ?: continue
				energy += internalEnergy
			}
		}

		// add the pair energies
		ForcefieldParams.forEachPair(atomsByMols, atomsByMols) { mola, atoma, molb, atomb, dist ->

			// map the atoms to the params molecules
			val molaParams = molParamsByMols.getValue(mola)
			val patoma = molToParams?.getValue(mola)?.getBOrThrow(atoma) ?: atoma
			val molbParams = molParamsByMols.getValue(molb)
			val patomb = molToParams?.getValue(molb)?.getBOrThrow(atomb) ?: atomb

			pairParams(molaParams, patoma, molbParams, patomb, dist)?.let { params ->
				energy += params.calcEnergy(atoma.pos.distance(atomb.pos))
			}
		}

		return energy
	}
}
