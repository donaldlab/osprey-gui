package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.toIdentitySet
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import java.util.*



class Amber96ConfSpaceParams : AmberConfSpaceParams(mapOf(
	MoleculeType.Protein to ForcefieldName.ff96
)) {
	override val forcefield = Forcefield.Amber96
}

class Amber14SBConfSpaceParams : AmberConfSpaceParams(mapOf(
	MoleculeType.Protein to ForcefieldName.ff14SB
)) {
	override val forcefield = Forcefield.Amber14SB
}

abstract class AmberConfSpaceParams(val ffnameOverrides: Map<MoleculeType,ForcefieldName>) : ForcefieldParams {

	/**
	 * The dielectric constant of the environment (aka its relative permittivity),
	 * which influences electrostatic calculations.
	 *
	 * This appears to be a magic number (for now).
	 * Maybe 6 is reasonable for a protein interior?
	 *
	 * For reference, this value seems to have a simlar relative permittivity
	 * to neoperene (6.7), but is far less than water (~80).
	 * See: https://en.wikipedia.org/wiki/Relative_permittivity
	 */
	var dielectric = 6.0

	/**
	 * If true, multiply the dielectric contstant by the atom pair distance (r)
	 * for electrostatic interactions.
	 */
	var distanceDependentDielectric = true

	/**
	 * Scaling to apply to the van der Waals calculations.
	 *
	 * The default value of 0.95 was determined empirically by Osprey developers
	 * to avoid specific issues caused by extreme van der Waals repulsion.
	 */
	var vdwScale = 0.95

	// TODO: expose the above options for configuration somewhere?


	private var topology: AmberTopology.Mapped? = null
	private val topologyOrThrow get() =
		topology ?: throw IllegalStateException("call setMolecules() before calling other methods")

	override fun setMolecules(mols: List<Molecule>) {

		// calc all the amber types
		val molsAndTypes = mols.map { mol ->

			// find the forcefield name
			val type = mol.findTypeOrThrow()
			val ffname = ffnameOverrides[type] ?: type.defaultForcefieldNameOrThrow

			// get the amber types
			mol to mol.calcTypesAmber(ffname)
		}

		// calculate any frcmods we may need
		val frcmods = molsAndTypes.mapNotNull { (mol, types) ->
			mol.calcModsAmber(types)
		}

		// calculate the amber "topology" which has the real forcefield parameters in it
		val params = molsAndTypes.calcParamsAmber(frcmods)
		val top = TopIO.read(params.top)

		// TEMP
		println("num distinct charges: ${top.charges.toSet().size}")

		// read the amber forcefield params from the toplogy file
		topology = top.mapTo(mols)
	}

	/** Amber forcefields don't have internal energies */
	override fun internalEnergy(mol: Molecule, atom: Atom) = null

	inner class PairParams(
		val esQ: Double,
		val vdwA: Double,
		val vdwB: Double
	) : ForcefieldParams.ParamsList {

		// TODO: should we include torsion parameters?!

		override val list = listOf(
			esQ,
			vdwA,
			vdwB
		)

		/**
		 * See Amber manual, Eqn 14.1
		 * But we've modified the electrostatic calculations to use a
		 * distance-dependent dielectric (ie 1/r2 instead of 1/r) when needed.
		 */
		fun calcEnergy(r: Double): Double {

			// calculate the electrostatics energy
			val r2 = r*r
			val es = if (distanceDependentDielectric) {
				esQ/r2
			} else {
				esQ/r
			}

			// calculate the van der Waals energy
			val r6 = r2*r2*r2
			val r12 = r6*r6
			val vdw = vdwA/r12 - vdwB/r6

			return es + vdw
		}
	}

	// Amber forcefields only have "non-bonded" interactions between atoms at least 1-4 bonded (ie >= 3 bonds away)
	private fun inRange(dist: Int?) =
		dist == null || dist >= 3

	override fun pairParams(mola: Molecule, atoma: Atom, molb: Molecule, atomb: Atom, dist: Int?): PairParams? {

		if (!inRange(dist)) {
			return null
		}

		val top = topologyOrThrow

		// get the electrostatic params
		// NOTE: the coulomb factor (~322.05) is already pre-multiplied into the charges
		// see: Amber manual 14.1.7, Partial Charges
		var esQ = (top.charge(atoma) * top.charge(atomb))/dielectric

		// get the van der Waals params
		// NOTE: Lorentz/Berthelot mixing rules already pre-applied to A and B
		// see: Amber manual 14.1.7, Van der Waals Parameters
		var (vdwA, vdwB) = top.vdw(atoma, atomb)

		// apply the parametric vdW scaling
		vdwA *= vdwScale
		vdwB *= vdwScale

		// 1-4 bonded atoms have scaled interactions
		// see: Amber manual 14.1.6, 1-4 Non-Bonded Interaction Scaling
		if (dist == 3) {
			esQ /= top.chargeDivisor14(atoma, atomb)
			top.vdwDivisor14(atoma, atomb).let {
				vdwA /= it
				vdwB /= it
			}
		}

		return PairParams(esQ, vdwA, vdwB)
	}

	override fun calcEnergy(atomsByMol: Map<Molecule,List<Atom>>): Double {

		var energy = 0.0

		// add the pair energies
		ForcefieldParams.forEachPair(atomsByMol, atomsByMol) { mola, atoma, molb, atomb, dist ->
			pairParams(mola, atoma, molb, atomb, dist)?.let { params ->
				energy += params.calcEnergy(atoma.pos.distance(atomb.pos))
			}
		}

		return energy
	}

	class Analysis : ForcefieldParams.Analysis {

		data class AtomInfo(val charge: Double, val type: String)

		val infos = IdentityHashMap<Atom,AtomInfo>()

		override fun findChangedAtoms(originalAnalysis: ForcefieldParams.Analysis): Set<Atom> {
			originalAnalysis as Analysis
			return originalAnalysis.infos
				.filter { (atom, originalInfo) ->
					infos.getValue(atom) != originalInfo
				}
				.map { (atom, _) -> atom }
				.toIdentitySet()
		}
	}

	override fun analyze(atomsByMol: Map<Molecule,Set<Atom>>) = Analysis().apply {
		val top = topologyOrThrow
		for ((_, atoms) in atomsByMol) {
			for (atom in atoms) {
				infos[atom] = Analysis.AtomInfo(
					charge = top.charge(atom),
					type = top.atomType(atom)
				)
			}
		}
	}
}
