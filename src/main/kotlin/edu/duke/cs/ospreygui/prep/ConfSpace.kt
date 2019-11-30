package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.toIdentitySet
import edu.duke.cs.molscope.tools.associateIdentity
import edu.duke.cs.molscope.tools.identityHashSet
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.io.ConfLib
import java.util.*


class ConfSpace(val mols: List<Pair<MoleculeType,Molecule>>) {

	// TODO: edit the name in the UI somewhere?
	var name = "Conformation Space"

	val designPositionsByMol: MutableMap<Molecule,MutableList<DesignPosition>> = IdentityHashMap()

	/**
	 * Collect all the positions for all the molecules in a stable order.
	 */
	fun positions(): List<DesignPosition> =
		designPositionsByMol
			.toList()
			.sortedBy { (mol, _) -> mol.name }
			.flatMap { (_, positions) -> positions }

	/**
	 * Collect all the unique fragments from the conf space.
	 */
	fun fragments(): List<ConfLib.Fragment> =
		designPositionsByMol
			.values
			.flatten()
			.mapNotNull { positionConfSpaces[it] }
			.flatMap { it.confs.keys + it.wildTypeFragment }
			.filterNotNull()
			.toCollection(identityHashSet())
			.sortedBy { it.id }

	class PositionConfSpace {

		var wildTypeFragment: ConfLib.Fragment? = null
		val mutations: MutableSet<String> = HashSet()
		val confs: MutableMap<ConfLib.Fragment,MutableSet<ConfLib.Conf>> = IdentityHashMap()

		/**
		 * Returns true iff the position allows a sequence type other than the wildtype.
		 */
		fun isMutable() =
			mutations.any { it != wildTypeFragment?.type }

		fun numConfs() =
			confs.values.sumBy { it.size }

		class MotionSettings(
			var includeHGroupRotations: Boolean,
			var dihedralRadiusDegrees: Double
		) {
			companion object {
				// blank for now, but defined so it can be extended
			}
		}
		val motionSettings: MutableMap<ConfLib.Fragment,MotionSettings> = IdentityHashMap()
	}
	class PositionConfSpaces {

		private val confSpaces: MutableMap<DesignPosition,PositionConfSpace> = IdentityHashMap()

		operator fun get(pos: DesignPosition) = confSpaces[pos]
		fun getOrMake(pos: DesignPosition) = confSpaces.getOrPut(pos) { PositionConfSpace() }
		fun remove(pos: DesignPosition) = confSpaces.remove(pos)
	}
	val positionConfSpaces = PositionConfSpaces()

	/**
	 * Get all the atoms that aren't part of design positions.
	 */
	fun fixedAtoms(): Map<Molecule,Set<Atom>> =
		mols
			.associateIdentity { (_, mol) ->
				val posAtoms = (designPositionsByMol[mol]
					?.flatMap { it.currentAtoms }
					?: emptyList())
					.toIdentitySet()
				mol to mol.atoms
					.filter { it !in posAtoms }
					.toIdentitySet()
			}

	/**
	 * Automatically undoes any conformational changes to the given design positions
	 * after executing the supplied function.
	 */
	inline fun <R> backupPositions(vararg positions: DesignPosition, block: () -> R): R {

		// save the original conformations
		val backupFrags = positions
			.toList()
			.associateIdentity { pos -> pos to pos.makeFragment("backup", "backup") }

		try {
			return block()
		} finally {

			// restore the original conformations
			for ((pos, backupFrag) in backupFrags) {
				pos.setConf(backupFrag, backupFrag.confs.values.first())
			}
		}
	}

	companion object {
		// blank for now, but defined so it can be extended
	}
}
