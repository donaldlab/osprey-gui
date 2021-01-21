package edu.duke.cs.ospreygui.forcefield

import edu.duke.cs.molscope.molecule.*


interface ForcefieldParams {

	val forcefield: Forcefield

	/**
	 * Any settings needed by the Osprey runtime to calculate this forcefield.
	 */
	fun settings(): Map<String,Any> = emptyMap()

	/**
	 * An opaque type the parameterizer can use to store parameters for an atom.
	 */
	interface AtomParams {

		/**
		 * Return the internal energy for this atom, if any.
		 * The atom index comes from the ConfSpaceIndex.
		 */
		fun internalEnergy(): Double?

		/**
		 * Override equals(), to tell subclasses they should explicitly implement it.
		 * The default identity implementation will always be wrong for this application.
		*/
		override fun equals(other: Any?): Boolean
	}

	/**
	 * An opaque type the parameterizer can use to store parameters for a molecule.
	 *
	 * Useful for computing atom internal energies.
	 */
	interface AtomsParams {

		/**
		 * Gets the atom params for this atom.
		 */
		operator fun get(atomi: Int): AtomParams?
	}

	/**
	 * Compute the forcefield parameters for a molecule.
	 */
	fun parameterizeAtoms(mol: Molecule, atomIndex: AtomIndex, netCharge: Int?): AtomsParams


	interface AtomPairParams {
		val list: List<Double>
		fun calcEnergy(r: Double): Double
	}

	/**
	 * An opaque type the parameterizer can use to store parameters for a atom pairs.
	 */
	interface AtomPairsParams {

		/**
		 * Return the forcefield parameters for this atom pair interaction, if any.
		 */
		operator fun get(moli1: Int, atomi1: Int, moli2: Int, atomi2: Int, dist: Int?): AtomPairParams?
	}

	data class MolInfo(
		val moli: Int,
		val mol: Molecule,
		val atomsParams: AtomsParams,
		/**
		 * Assigns a unique number to each atom in the molecule.
		 * With multiple molecules, each atom should appear in only one (or no) index.
		 */
		val atomIndex: AtomIndex
	)

	/**
	 * Compute the forcefield parameters for a list of molecules.
	 */
	fun parameterizeAtomPairs(infos: List<MolInfo>): AtomPairsParams

	data class AtomsInfo(
		val atomsParams: AtomsParams,
		val preferredAtomIndices: Set<Int>,
		val ignoredAtomIndices: Set<Int>
	)

	/**
	 * Combines together atoms params from two variations of the same molecule.
	 * Useful for eg applying two mutations to the same molecule.
	 * If the two atoms parameters disagree on params for an atom, the tie should be broken
	 * by picking the atom params from the atoms params whose given atom indices contain the disagreeable atom's index.
	 * The two given sets of atom indices should not overlap.
	 * If a tie can't be broken with the preferred atom indices, the implementation should throw an exception.
	 */
	fun combineAtomsParams(info1: AtomsInfo, info2: AtomsInfo): AtomsParams
}
