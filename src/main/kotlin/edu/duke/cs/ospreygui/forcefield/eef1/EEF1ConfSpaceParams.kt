package edu.duke.cs.ospreygui.forcefield.eef1

import cuchaz.kludge.tools.sqrt
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
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

	data class SingleParams(
		val dGref: Double
	) : ForcefieldParams.ParamsList(
		dGref
	) {

		constructor (type: EEF1.AtomType) : this(
			type.dGref
		)

		fun calcEnergy(): Double =
			dGref
	}

	override fun singleParams(mol: Molecule, atom: Atom): SingleParams? {
		val type = atom.atomTypeEEF1(mol) ?: return null
		return SingleParams(type)
	}

	data class PairParams(
		val vdwRadiusa: Double,
		val lambdaa: Double,
		val vdwRadiusb: Double,
		val lambdab: Double,
		val alpha1: Double,
		val alpha2: Double
	) : ForcefieldParams.ParamsList(
		vdwRadiusa,
		lambdaa,
		vdwRadiusb,
		lambdab,
		alpha1,
		alpha2
	) {

		constructor (a: EEF1.AtomType, b: EEF1.AtomType) : this(
			a.vdwRadius,
			a.lambda,
			b.vdwRadius,
			b.lambda,
			trigConst*a.dGfree*b.volume/a.lambda,
			trigConst*b.dGfree*a.volume/b.lambda
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

		val atype = atoma.atomTypeEEF1(mola) ?: return null
		val btype = atomb.atomTypeEEF1(molb) ?: return null
		return PairParams(atype, btype)
	}

	override fun calcEnergy(atomsByMol: Map<Molecule,List<Atom>>): Double {

		// pre-compute all the atom types
		val types = ForcefieldParams.AtomParams<EEF1.AtomType?>().apply {
			for ((mol, atoms) in atomsByMol) {
				for (atom in atoms) {
					this[mol, atom] = atom.atomTypeEEF1(mol)
				}
			}
		}

		var energy = 0.0

		ForcefieldParams.forEachPair(atomsByMol, atomsByMol) { mola, atoma, molb, atomb, dist ->
			if (atoma === atomb) {

				// single energy
				types[mola, atoma]?.let { type ->
					energy += SingleParams(type).calcEnergy()
				}

			} else {

				// pair energy
				if (inRange(dist)) {
					types[mola, atoma]?.let { typea ->
						types[molb, atomb]?.let { typeb ->
							energy += PairParams(typea, typeb).calcEnergy(atoma.pos.distance(atomb.pos))
						}
					}
				}
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
		for ((mol, atoms) in atomsByMol) {
			for (atom in atoms) {
				types[atom] = atom.atomTypeEEF1(mol)
			}
		}
	}
}
