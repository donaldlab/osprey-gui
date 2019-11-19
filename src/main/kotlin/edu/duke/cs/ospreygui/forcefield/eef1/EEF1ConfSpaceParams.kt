package edu.duke.cs.ospreygui.forcefield.eef1

import cuchaz.kludge.tools.sqrt
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.toIdentitySet
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import java.util.*
import kotlin.math.PI
import kotlin.math.exp


class EEF1ConfSpaceParams : ForcefieldParams {

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


	private var atomTypes: ForcefieldParams.AtomParams<EEF1.AtomType?>? = null
	private val atomTypesOrThrow get() =
		atomTypes ?: throw IllegalStateException("call setMolecules() before calling other methods")

	override fun setMolecules(mols: List<Molecule>, smallMolNetCharges: Map<Molecule,Int>) {
		atomTypes = ForcefieldParams.AtomParams<EEF1.AtomType?>().apply {
			for (mol in mols) {
				for (atom in mol.atoms) {
					this[mol, atom] = atom.atomTypeEEF1(mol)
				}
			}
		}
	}

	override fun internalEnergy(mol: Molecule, atom: Atom): Double? {
		val type = atomTypesOrThrow[mol, atom] ?: return null
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

	// EEF1 only has interactions between atoms at least 1-4 bonded (ie >= 3 bonds away)
	private fun inRange(dist: Int?) =
		dist == null || dist >= 3

	override fun pairParams(mola: Molecule, atoma: Atom, molb: Molecule, atomb: Atom, dist: Int?): PairParams? {

		if (!inRange(dist)) {
			return null
		}

		val atomTypes = atomTypesOrThrow
		val atype = atomTypes[mola, atoma] ?: return null
		val btype = atomTypes[molb, atomb] ?: return null
		return PairParams(atype, btype)
	}

	override fun calcEnergy(atomsByMol: Map<Molecule,List<Atom>>): Double {

		var energy = 0.0

		// add the internal energies
		for ((mol, atoms) in atomsByMol) {
			for (atom in atoms) {
				val internalEnergy = internalEnergy(mol, atom) ?: continue
				energy += internalEnergy
			}
		}

		// add the pair energies
		ForcefieldParams.forEachPair(atomsByMol, atomsByMol) { mola, atoma, molb, atomb, dist ->
			pairParams(mola, atoma, molb, atomb, dist)?.let { params ->
				energy += params.calcEnergy(atoma.pos.distance(atomb.pos))
			}
		}

		return energy
	}

	class Analysis : ForcefieldParams.Analysis {

		val types = IdentityHashMap<Atom,EEF1.AtomType>()

		override fun findChangedAtoms(originalAnalysis: ForcefieldParams.Analysis): Set<Atom> {
			originalAnalysis as Analysis
			return originalAnalysis.types
				.filter { (atom, originalType) ->
					types.getValue(atom) != originalType
				}
				.map { (atom, _) -> atom }
				.toIdentitySet()
		}
	}

	override fun analyze(atomsByMol: Map<Molecule,Set<Atom>>) = Analysis().apply {
		val atomTypes = atomTypesOrThrow
		for ((mol, atoms) in atomsByMol) {
			for (atom in atoms) {
				types[atom] = atomTypes[mol, atom]
			}
		}
	}
}
