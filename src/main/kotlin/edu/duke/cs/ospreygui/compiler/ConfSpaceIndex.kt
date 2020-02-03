package edu.duke.cs.ospreygui.compiler

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.prep.DesignPosition


/**
 * Establishes an authritative order for all the positions and conformations in the space.
 * Ignores any design positions that have no position conformation space.
 */
class ConfSpaceIndex(val confSpace: ConfSpace) {

	inner class ConfInfo(
		val posInfo: PosInfo,
		val fragInfo: FragInfo,
		val confConfSpace: ConfSpace.ConfConfSpace,
		val index: Int
	) {
		val conf get() = confConfSpace.conf

		val id = "${fragInfo.frag.id}:${conf.id}"
	}

	inner class FragInfo(
		val posInfo: PosInfo,
		val frag: ConfLib.Fragment,
		val index: Int
	) {

		/**
		 * Append the conf atoms to the fixed atoms for this design position,
		 * in a consistent order.
		 */
		fun orderAtoms(fixedAtoms: List<FixedAtoms.DynamicInfo>): List<Atom> {
			return ArrayList<Atom>().apply {

				// add the fixed atoms in the existing order
				for (atomInfo in fixedAtoms) {
					add(atomInfo.atom)
				}

				// add the conformation atoms in the existing order
				for (atomInfo in frag.atoms) {

					// find the corresponding atom at the design position
					add(posInfo.pos.atomResolverOrThrow.resolveOrThrow(atomInfo))
				}
			}
		}
	}

	inner class PosInfo(
		val pos: DesignPosition,
		val posConfSpace: ConfSpace.PositionConfSpace,
		val index: Int
	) {

		// index the fragments
		val fragments =
			posConfSpace.confs.fragments()
				.mapIndexed { i, frag -> FragInfo(this, frag, i) }

		// index the conformations
		val confs =
			fragments
				.flatMap { fragInfo ->
					posConfSpace.confs.getByFragment(fragInfo.frag)
						.map { space -> fragInfo to space }
				}
				.mapIndexed { i, (fragInfo, space) ->
					ConfInfo(this, fragInfo, space, i)
				}

		/**
		 * Iterates over the conformations in the position conf space,
		 * setting each conformation to the design position in turn.
		 *
		 * Makes sure to get a lock on the molecule first before modifying it.
		 */
		fun forEachConf(molLocker: MoleculeLocker, block: (ConfInfo) -> Unit) {

			// backup the original conformation
			val backupFrag = pos.makeFragment("backup", "backup")

			try {

				for (confInfo in confs) {
					molLocker.lock(pos.mol) {
						pos.setConf(confInfo.fragInfo.frag, confInfo.conf)
					}
					block(confInfo)
				}

			} finally {

				// restore the original conformation
				molLocker.lock(pos.mol) {
					pos.setConf(backupFrag, backupFrag.confs.values.first())
				}
			}
		}

		/**
		 * Iterates over the fragments in the position conf space, setting an
		 * arbitrary conformation of each fragment to the design position in turn.
		 *
		 * Makes sure to get a lock on the molecule first before modifying it.
		 */
		fun forEachFrag(molLocker: MoleculeLocker, block: (FragInfo, ConfInfo) -> Unit) {

			// backup the original conformation
			val backupFrag = pos.makeFragment("backup", "backup")

			try {

				for (fragInfo in fragments) {

					// pick an arbitrary conformation from the fragment
					val confInfo = confs.first { it.fragInfo === fragInfo }

					molLocker.lock(pos.mol) {
						pos.setConf(fragInfo.frag, confInfo.conf)
					}
					block(fragInfo, confInfo)
				}

			} finally {

				// restore the original conformation
				molLocker.lock(pos.mol) {
					pos.setConf(backupFrag, backupFrag.confs.values.first())
				}
			}
		}
	}

	// choose an order for the design positions and assign indices
	val positions =
		confSpace
			.designPositionsByMol
			.flatMap { (_, positions) -> positions }
			.mapNotNull { pos -> confSpace.positionConfSpaces[pos]?.let { pos to it } }
			.mapIndexed { i, (pos, posConfSpace) -> PosInfo(pos, posConfSpace, i) }

	// collect all the molecules
	val mols =
		confSpace.mols
			.map { (_, mol) -> mol }
}
