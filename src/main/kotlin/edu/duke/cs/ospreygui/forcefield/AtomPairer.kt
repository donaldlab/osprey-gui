package edu.duke.cs.ospreygui.forcefield

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.toIdentitySet


typealias FfparamsPairFunc = (info1: AtomPairer.MolInfo, atomi1: Int, info2: AtomPairer.MolInfo, atomi2: Int, distance: Int?) -> Unit

object AtomPairer {

	/**
	 * Metadata about a molecule to use in the atom pairing.
	 */
	open class MolInfo(
		/**
		 * An index for the molecule.
		 * All molecules in a pairing should have a unique index.
		 */
		val moli: Int,
		/** The molecule */
		val mol: Molecule,
		/** The atoms in the molecule that should be paired */
		val atoms: Iterable<Atom>,
		/**
		 * An index for the atoms that should be paired.
		 * Non-paired atoms need not be in the index.
		 * Atom indices need only be unique within this molecule.
		 */
		val atomIndex: AtomIndex
	)

	fun molPairs(infos1: List<MolInfo>, infos2: List<MolInfo>): List<MolPairInfo> = ArrayList<MolPairInfo>().apply {
		for (mol1 in infos1) {
			for (mol2 in infos2.filter { mol1.moli <= it.moli }) {
				add(MolPairInfo(mol1, mol2))
			}
		}
	}

	open class MolPairInfo(
		val mol1: MolInfo,
		val mol2: MolInfo
	) {

		fun forEach(func: FfparamsPairFunc) {
			if (mol1.moli == mol2.moli) {
				forEachIntramol(mol1, mol2, func)
			} else {
				forEachIntermol(mol1, mol2, func)
			}
		}

		private fun forEachIntramol(info1: MolInfo, info2: MolInfo, func: FfparamsPairFunc) {

			assert (info1.mol === info2.mol)
			val mol = info1.mol

			fun Atom.index1() = info1.atomIndex.getOrThrow(this)
			fun Atom.index2() = info2.atomIndex.getOrThrow(this)

			// build a lookup table for the info2 atoms
			val atomLookup = info2.atoms
				.toList()
				.toIdentitySet()

			// find all the atom pairs and their bond distances using BFS on the bond graph
			for (atom1 in info1.atoms) {
				val atomi1 = atom1.index1()
				mol
					.bfs(
						source = atom1,
						visitSource = false,
						shouldVisit = { _, _, _ -> true }
					)
					// limit only to the atoms in the input set
					.filter { it.atom in atomLookup }
					.forEach { (atom2, dist) ->
						val atomi2 = atom2.index2()

						// and only visit each atom pair once
						val useIt = if (info1 === info2) {
							// both sides use the same domain, use the order to filter
							atomi1 >= atomi2
						} else {
							// both sides use different domains, can't share pairs
							true
						}
						if (useIt) {
							func(info1, atomi1, info2, atomi2, dist)
						}
					}
			}
		}

		private fun forEachIntermol(info1: MolInfo, info2: MolInfo, func: FfparamsPairFunc) {

			// easy peasy, all atom pairs are nonbonded
			for (atom1 in info1.atoms) {
				val atomi1 = info1.atomIndex.getOrThrow(atom1)
				for (atom2 in info2.atoms) {
					val atomi2 = info2.atomIndex.getOrThrow(atom2)
					func(info1, atomi1, info2, atomi2, null)
				}
			}
		}
	}
}
