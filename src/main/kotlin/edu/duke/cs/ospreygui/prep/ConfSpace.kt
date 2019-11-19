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
	 * Apply all the conformations in the conf space, one-at-a-time,
	 * and then restore the molecule to its original state.
	 */
	fun forEachConf(block: (DesignPosition, ConfLib.Fragment, ConfLib.Conf) -> Unit) {
		for (pos in positions()) {
			forEachConf(pos) { frag, conf ->
				block(pos, frag, conf)
			}
		}
	}

	inline fun <R> backupPosition(pos: DesignPosition, block: () -> R): R {

		// save the original conf
		val originalFrag = pos.makeFragment("original", "Original")

		try {
			return block()
		} finally {

			// restore the original conf
			pos.setConf(originalFrag, originalFrag.confs.values.first())
		}
	}

	/**
	 * Iterates over the conformations in the position conf space,
	 * setting each conformation to the design position in turn.
	 * Conformations are sorted (by fragment id, then conf id) so
	 * the iteration order is deterministic.
	 */
	fun forEachConf(pos: DesignPosition, func: (ConfLib.Fragment, ConfLib.Conf) -> Unit) {

		val posConfSpace = positionConfSpaces[pos] ?: return

		backupPosition(pos) {
			posConfSpace.confs
				.toList()
				.sortedBy { (frag, _) -> frag.id }
				.forEach { (frag, confs) ->
					confs
						.sortedBy { it.id }
						.forEach { conf ->
							pos.setConf(frag, conf)
							func(frag, conf)
						}
				}
		}
	}

	companion object {
		// blank for now, but defined so it can be extended
	}
}
