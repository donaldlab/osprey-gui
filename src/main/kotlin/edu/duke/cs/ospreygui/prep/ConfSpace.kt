package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.toIdentitySet
import edu.duke.cs.molscope.tools.associateIdentity
import edu.duke.cs.molscope.tools.identityHashSet
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.io.ConfLib
import java.math.BigInteger
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

		data class MotionSettings(
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

		fun sequenceSpaceSize(): BigInteger =
			confSpaces.values
				.takeIf { it.isNotEmpty() }
				?.map { it.mutations.size.toBigInteger() }
				?.reduce { a, b -> a.multiply(b) }
				?: BigInteger.ZERO

		fun confSpaceSize(): BigInteger =
			confSpaces.values
				.takeIf { it.isNotEmpty() }
				?.map { it.numConfs().toBigInteger() }
				?.reduce { a, b -> a.multiply(b) }
				?: BigInteger.ZERO
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

	/**
	 * Makes a copy of the selected molecules along with all their
	 * design positions into a new conformation space
	 */
	fun copy(selMols: List<Molecule> = mols.map { (_, mol) -> mol }): ConfSpace {

		val old = this

		// match the selected molecules to the conf space
		val oldTypedMols = selMols.map { selMol ->
			old.mols
				.find { (_, mol) -> mol === selMol }
				?: throw NoSuchElementException("this conformation space didn't contain the selected molecule: $selMol")
		}
		val oldMols = oldTypedMols.map { (_, mol) -> mol }

		// make the new conf space from copies of the old molecules
		val new = ConfSpace(oldTypedMols.map { (type, mol) ->
			type to mol.copy()
		})
		val newMols = new.mols.map { (_, mol) -> mol }

		// copy the name
		new.name = old.name

		for ((oldMol, newMol) in oldMols.zip(newMols)) {

			// make an atom map across the two molecules
			val oldToNew = oldMol.mapAtomsByValueTo(newMol)

			// copy the design positions
			val oldPositions = old.designPositionsByMol[oldMol] ?: continue

			val newPositions = ArrayList<DesignPosition>()
			new.designPositionsByMol[newMol] = newPositions

			for (oldPos in oldPositions) {

				val newPos = DesignPosition(
					oldPos.name,
					oldPos.type,
					newMol
				)

				// copy the anchors
				newPos.anchorGroups.addAll(oldPos.anchorGroups.map { oldAnchors ->
					oldAnchors
						.map { oldAnchor ->
							oldAnchor.copyToPos(newPos, oldToNew)
						}
						.toMutableList()
				})

				// copy the current atoms
				newPos.currentAtoms.addAll(oldPos.currentAtoms.map { atom ->
					oldToNew.getBOrThrow(atom)
				})

				newPositions.add(newPos)

				// copy the position conf space
				val oldPosConfSpace = old.positionConfSpaces[oldPos] ?: continue
				val newPosConfSpace = new.positionConfSpaces.getOrMake(newPos)

				// copy the wild-type fragment, if needed
				oldPosConfSpace.wildTypeFragment?.let { oldWTFrag ->
					val oldWTConf = oldWTFrag.confs.values.first()
					newPosConfSpace.wildTypeFragment = newPos.makeFragment(
						fragId = oldWTFrag.id,
						fragName = oldWTFrag.name,
						confId = oldWTConf.id,
						confName = oldWTConf.name,
						motions = oldWTFrag.motions
					)

					// TODO: reset the atom ids to match the old atom ids?
				}

				// copy the mutations
				newPosConfSpace.mutations.addAll(oldPosConfSpace.mutations)

				// copy the conformations
				for ((frag, confs) in oldPosConfSpace.confs) {
					newPosConfSpace.confs[frag] = confs.toCollection(identityHashSet())
				}

				// copy the motions settings
				for ((frag, oldSettings) in oldPosConfSpace.motionSettings) {
					newPosConfSpace.motionSettings[frag] = oldSettings.copy()
				}
			}
		}

		return new
	}

	companion object {
		// blank for now, but defined so it can be extended
	}
}
