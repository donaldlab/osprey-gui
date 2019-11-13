package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.tools.identityHashSet
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.io.ConfLib
import java.util.*


class ConfSpace(val mols: List<Pair<MoleculeType,Molecule>>) {

	// TODO: edit the name in the UI somewhere?
	var name = "Conformation Space"

	val designPositionsByMol: MutableMap<Molecule,MutableList<DesignPosition>> = HashMap()

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

		class DofSettings(
			var includeHGroupRotations: Boolean,
			var dihedralRadiusDegrees: Double
		) {
			companion object {
				// blank for now, but defined so it can be extended
			}
		}
		val dofSettings: MutableMap<ConfLib.Fragment,DofSettings> = IdentityHashMap()
	}
	class PositionConfSpaces {

		private val confSpaces: MutableMap<DesignPosition,PositionConfSpace> = IdentityHashMap()

		operator fun get(pos: DesignPosition) = confSpaces[pos]
		fun getOrMake(pos: DesignPosition) = confSpaces.getOrPut(pos) { PositionConfSpace() }
		fun remove(pos: DesignPosition) = confSpaces.remove(pos)
	}
	val positionConfSpaces = PositionConfSpaces()

	companion object {
		// blank for now, but defined so it can be extended
	}
}
