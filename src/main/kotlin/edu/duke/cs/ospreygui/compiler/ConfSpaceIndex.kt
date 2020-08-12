package edu.duke.cs.ospreygui.compiler

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.prep.Assignments
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.prep.DesignPosition
import edu.duke.cs.ospreygui.prep.PosAssignment


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
		 *
		 * Only return atoms from the assigned molecule.
		 */
		fun orderAtoms(fixedAtoms: List<FixedAtoms.DynamicInfo>, assignmentInfo: Assignments.AssignmentInfo): List<Atom> {
			return ArrayList<Atom>().apply {

				// add the fixed atoms in the existing order
				for (atomInfo in fixedAtoms) {
					add(assignmentInfo.maps.atoms.getBOrThrow(atomInfo.atom))
				}

				// add the conformation atoms in the existing order
				for (atomInfo in frag.atoms) {
					add(assignmentInfo.confSwitcher.atomResolverOrThrow.resolveOrThrow(atomInfo))
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
						.sortedBy { space -> space.conf.id }
						.map { space -> fragInfo to space }
				}
				.mapIndexed { i, (fragInfo, space) ->
					ConfInfo(this, fragInfo, space, i)
				}

		/**
		 * Iterates over the conformations in the position conf space,
		 * returning assignments for each conformation in turn
		 */
		fun forEachConf(block: (Assignments.AssignmentInfo, ConfInfo) -> Unit) {

			for (confInfo in confs) {

				// make the assignments
				val assignment = PosAssignment(pos, confInfo.fragInfo.frag, confInfo.conf)
				val assignmentInfo = Assignments(assignment).assignmentInfos.getValue(assignment)

				block(assignmentInfo, confInfo)
			}
		}

		/**
		 * Iterates over the fragments in the position conf space,
		 * returning assignments for an arbitrary conformation of each fragment in turn.
		 */
		fun forEachFrag(block: (Assignments.AssignmentInfo, ConfInfo) -> Unit) {

			for (fragInfo in fragments) {

				// choose an arbitrary conformation from the fragment
				val confInfo = confs.first { it.fragInfo === fragInfo }

				// make the assignment
				val assignment = PosAssignment(pos, fragInfo.frag, confInfo.conf)
				val assignmentInfo = Assignments(assignment).assignmentInfos.getValue(assignment)

				block(assignmentInfo, confInfo)
			}
		}
	}

	// choose an order for the molecules
	val mols =
		confSpace.mols
			.map { (_, mol) -> mol }
			.sortedBy { it.name }

	// choose an order for the design positions and assign indices
	val positions =
		mols
			.mapNotNull { mol ->
				confSpace.designPositionsByMol[mol]
					?.mapNotNull { pos ->
						confSpace.positionConfSpaces[pos]?.let { pos to it }
					}
			}
			.flatten()
			.mapIndexed { i, (pos, posConfSpace) -> PosInfo(pos, posConfSpace, i) }
}


fun Pair<ConfSpaceIndex.PosInfo,ConfSpaceIndex.PosInfo>.forEachFrag(block: (Assignments.AssignmentInfo, ConfSpaceIndex.ConfInfo, Assignments.AssignmentInfo, ConfSpaceIndex.ConfInfo) -> Unit) {

	val (posInfo1, posInfo2) = this

	for (fragInfo1 in posInfo1.fragments) {

		// choose an arbitrary conformation from the fragment
		val confInfo1 = posInfo1.confs.first { it.fragInfo === fragInfo1 }

		for (fragInfo2 in posInfo2.fragments) {

			// choose an arbitrary conformation from the fragment
			val confInfo2 = posInfo2.confs.first { it.fragInfo === fragInfo2 }

			// make the assignments
			val assignment1 = PosAssignment(posInfo1.pos, fragInfo1.frag, confInfo1.conf)
			val assignment2 = PosAssignment(posInfo2.pos, fragInfo2.frag, confInfo2.conf)
			val assignments = Assignments(assignment1, assignment2)
			val assignmentInfo1 = assignments.assignmentInfos.getValue(assignment1)
			val assignmentInfo2 = assignments.assignmentInfos.getValue(assignment2)

			block(assignmentInfo1, confInfo1, assignmentInfo2, confInfo2)
		}
	}
}
