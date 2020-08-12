package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.MoleculeMaps
import edu.duke.cs.molscope.tools.associateIdentity
import edu.duke.cs.molscope.tools.toIdentitySet
import edu.duke.cs.ospreygui.io.ConfLib


class PosAssignment(
	val pos: DesignPosition,
	val frag: ConfLib.Fragment,
	val conf: ConfLib.Conf
) {
	override fun toString() =
		"PosAssignment[pos=${pos.name}, frag=${frag.id}, conf=${conf.id}]"
}


class Assignments(val assignments: List<PosAssignment>) {

	constructor(vararg assignments: PosAssignment) : this(assignments.toList())

	class AssignmentInfo(
		val mol: Molecule,
		val maps: MoleculeMaps,
		val confSwitcher: ConfSwitcher
	)

	val assignmentInfos: Map<PosAssignment,AssignmentInfo> = run {

		// get just the source molecules from the assignments
		// and keep them in a deterministic order
		val srcMols = assignments
			.map { it.pos.mol }
			.toIdentitySet()
			.sortedBy { it.name }

		// copy the molecules
		val dstMols = srcMols
			.associateIdentity { it to it.copyWithMaps() }

		// make the assignments
		assignments.associateIdentity { assignment ->
			val srcMol = assignment.pos.mol
			val (dstMol, maps) = dstMols.getValue(srcMol)
			val confSwitcher = ConfSwitcher(assignment.pos, dstMol, maps).apply {
				setConf(assignment.frag, assignment.conf)
			}
			assignment to AssignmentInfo(dstMol, maps, confSwitcher)
		}
	}
}
