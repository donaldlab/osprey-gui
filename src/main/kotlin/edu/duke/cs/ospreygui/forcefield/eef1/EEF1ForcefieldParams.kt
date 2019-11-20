package edu.duke.cs.ospreygui.forcefield.eef1

import cuchaz.kludge.tools.sqrt
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import java.util.*
import kotlin.math.PI
import kotlin.math.exp


class EEF1ForcefieldParams : ForcefieldParams {

	companion object {

		val trigConst = 2.0/(4.0*PI*PI.sqrt())
	}

	override val forcefield = Forcefield.EEF1

	/**
	 * Scaling to apply to the van der Waals calculations.
	 *
	 * The default value of 0.5 was determined empirically by Osprey developers
	 * to achieve good balance between the Amber96 and EEF1 forcefields.
	 */
	var scale = 0.5


	class MolParams(
		override val mol: Molecule
	) : ForcefieldParams.MolParams {

		val types = IdentityHashMap<Atom,EEF1.AtomType>()

		override fun isChanged(thisAtom: Atom, baseline: ForcefieldParams.MolParams, baseAtom: Atom): Boolean {

			baseline as MolParams

			return this.types.getValue(thisAtom) != baseline.types.getValue(baseAtom)
		}
	}

	override fun parameterize(mol: Molecule, netCharge: Int?) =
		MolParams(mol).apply {
			for (atom in mol.atoms) {
				types[atom] = atom.atomTypeEEF1(mol)
			}
		}


	override fun internalEnergy(molParams: ForcefieldParams.MolParams, atom: Atom): Double? {

		molParams as MolParams

		val type = molParams.types[atom] ?: return null
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
			val Xij = (r - vdwRadiusa)/lambdaa
			val Xji = (r - vdwRadiusb)/lambdab
			return -(alpha1*exp(-Xij*Xij) + alpha2*exp(-Xji*Xji))/r/r
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

		// map the atoms to our params molecules
		// and yes, shadow the names so we don't accidentally use the wrong atoms in the toplogy
		@Suppress("NAME_SHADOWING")
		val atoma = molaParams.findAtom(atoma)
		@Suppress("NAME_SHADOWING")
		val atomb = molbParams.findAtom(atomb)

		val atype = molaParams.types[atoma] ?: return null
		val btype = molbParams.types[atomb] ?: return null
		return PairParams(atype, btype)
	}

	override fun calcEnergy(atomsByMols: Map<Molecule,List<Atom>>, molParamsByMols: Map<Molecule,ForcefieldParams.MolParams>): Double {

		var energy = 0.0

		// add the internal energies
		for ((mol, atoms) in atomsByMols) {
			val molParams = molParamsByMols.getValue(mol) as MolParams
			for (atom in atoms) {
				val internalEnergy = internalEnergy(molParams, atom) ?: continue
				energy += internalEnergy
			}
		}

		// add the pair energies
		ForcefieldParams.forEachPair(atomsByMols, atomsByMols) { mola, atoma, molb, atomb, dist ->
			val molaParams = molParamsByMols.getValue(mola)
			val molbParams = molParamsByMols.getValue(molb)
			pairParams(molaParams, atoma, molbParams, atomb, dist)?.let { params ->
				energy += params.calcEnergy(atoma.pos.distance(atomb.pos))
			}
		}

		return energy
	}
}
