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
		val frag: ConfLib.Fragment,
		val conf: ConfLib.Conf,
		val index: Int
	) {
		val id = "${frag.id}:${conf.id}"

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

		// choose an order for the conformations and assign indices
		val confs =
			posConfSpace.confs.entries
				.sortedBy { (frag, _) -> frag.id }
				.flatMap { (frag, confs) ->
					confs
						.sortedBy { it.id }
						.map { frag to it }
				}
				.mapIndexed { i, (frag, conf) -> ConfInfo(this, frag, conf, i) }

		/**
		 * Iterates over the conformations in the position conf space,
		 * setting each conformation to the design position in turn.
		 */
		fun forEachConf(block: (ConfInfo) -> Unit) {
			confSpace.backupPositions(pos) {
				for (confInfo in confs) {
					pos.setConf(confInfo.frag, confInfo.conf)
					block(confInfo)
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
