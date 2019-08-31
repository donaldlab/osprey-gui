package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.AtomMap
import edu.duke.cs.molscope.molecule.Molecule
import org.joml.Vector3d
import java.util.*
import kotlin.NoSuchElementException
import kotlin.collections.ArrayList


class MinimizerInfo(val mol: Molecule) {

	// sander minimizations are currently configured to use implicit solvent
	// so make sure to filter out all the solvent molecules before minimizing

	// NOTE: Don't call any heavy-weight computations (like AmberTools) in this constructor,
	// so this class is safe to construct on any UI thread.
	// This class is just to prepare for heavy-weight computations later,
	// which should probably shouldn't run in UI threads.

	val partition: List<Pair<MoleculeType,Molecule>>
	val atomMap: AtomMap
	init {
		val (partition, atomMap) = mol.partitionAndAtomMap(combineSolvent = false)
		this.partition = partition
		this.atomMap = atomMap
	}

	val partitionWithoutSolvent =
		partition.filter { (moltype, _) -> moltype != MoleculeType.Solvent }

	/** in the original molecule, not the partition */
	val minimizableAtoms =
		partitionWithoutSolvent
			.flatMap { (_, mol) ->
				mol.atoms.map { atomMap.getAOrThrow(it) }
			}

	val originalCoords: List<Vector3d> =
		minimizableAtoms.map { Vector3d(it.pos) }

	var minimizedCoords: List<Vector3d>? = null

	fun setCoords(coords: List<Vector3d>) {
		minimizableAtoms.forEachIndexed { i, atom ->
			atom.pos.set(coords[i])
		}
	}
}

fun List<MinimizerInfo>.minimize(numSteps: Int) {

	val infosByAtomIndex = ArrayList<Pair<IntRange,MinimizerInfo>>()
	var atomIndex = 0

	// get the amber params for the combined molecules
	val frcmods = ArrayList<String>()
	val molsAndTypes = this
		.flatMap { info ->
			info.partitionWithoutSolvent
				.map { (moltype, mol) ->

					// but keep track of the atom indices,
					// so we can match the minimized coordinates back later
					val indexRange = atomIndex until atomIndex + mol.atoms.size
					atomIndex += mol.atoms.size
					infosByAtomIndex.add(indexRange to info)

					val types = mol.calcTypesAmber(moltype.defaultForcefieldNameOrThrow)

					mol.calcModsAmber(types)?.let { frcmods.add(it) }

					mol to types
				}
		}
	val params = molsAndTypes.calcParamsAmber(frcmods)

	val results = Sander.minimize(params.top, params.crd, numSteps)

	// grab the minimized coords for each mol
	val coordsByInfo = IdentityHashMap<MinimizerInfo,MutableList<Vector3d>>()
	results.coords.forEachIndexed { i, pos ->

		val info = infosByAtomIndex
			.find { (range, _) -> i in range }
			?.second
			?: throw NoSuchElementException("no MolInfo for minimized atom index $i")

		coordsByInfo.getOrPut(info) { ArrayList() }.add(pos)
	}

	// update the info with the minimized coords
	for ((info, coords) in coordsByInfo) {

		// just in case...
		assert(info.originalCoords.size == coords.size)

		info.minimizedCoords = coords
		info.setCoords(coords)
	}
}
