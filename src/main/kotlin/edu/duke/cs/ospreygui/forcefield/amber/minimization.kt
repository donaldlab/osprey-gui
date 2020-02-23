package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.AtomMap
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.tools.assert
import edu.duke.cs.ospreygui.io.OspreyService
import edu.duke.cs.ospreyservice.services.MinimizeRequest
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

	var unminimizedCoords: List<Vector3d>? = null

	fun captureCoords() {
		unminimizedCoords = minimizableAtoms.map { Vector3d(it.pos) }
	}

	var minimizedCoords: List<Vector3d>? = null

	fun setCoords(coords: List<Vector3d>) {
		minimizableAtoms.forEachIndexed { i, atom ->
			atom.pos.set(coords[i])
		}
	}
}

fun List<MinimizerInfo>.minimize(
	numSteps: Int,
	restrainedAtoms: List<Atom> = emptyList()
) {

	// capture the original coords
	for (info in this) {
		info.captureCoords()
	}

	val infosByAtomIndex = ArrayList<Pair<IntRange,MinimizerInfo>>()
	val indicesByAtom = IdentityHashMap<Atom,Int>()
	val atomsByIndex = ArrayList<Atom>()

	// get the amber params for the combined molecules
	val params = this
		.flatMap { info ->
			info.partitionWithoutSolvent
				.map { (moltype, mol) ->

					// but keep track of the atom indices,
					// so we can match the minimized coordinates back later
					val indexRange = atomsByIndex.size until atomsByIndex.size + mol.atoms.size
					infosByAtomIndex.add(indexRange to info)

					// and also so we can refer to specific atoms by index
					for (atomB in mol.atoms) {
						val atomA = info.atomMap.getAOrThrow(atomB)
						indicesByAtom[atomA] = atomsByIndex.size
						atomsByIndex.add(atomA)
					}

					val types = mol.calcTypesAmber(moltype)
					val frcmod = mol.calcModsAmber(types)
					AmberMolParams(mol, types, frcmod)
				}
		}
		.calcParamsAmber()

	// just in case, check the atom indices by matching atom names
	assert {
		TopIO.read(params.top).atomNames == flatMap { info -> info.minimizableAtoms.map { it.name } }
	}

	// convert restrained atoms into a sander-style restraint mask
	val restraintMask = if (restrainedAtoms.isNotEmpty()) {
		"@"	+ restrainedAtoms
			.map { indicesByAtom.getValue(it) + 1 } // atom indices in sander start with 1
			.joinToString(",")
	} else {
		null
	}

	// minimize it!
	val allCoords = OspreyService.minimize(MinimizeRequest(
		params.top,
		params.crd,
		numSteps,
		restraintMask
	)).coords

	// grab the minimized coords for each mol
	val coordsByInfo = IdentityHashMap<MinimizerInfo,MutableList<Vector3d>>()
	allCoords.forEachIndexed { i, pos ->

		val info = infosByAtomIndex
			.find { (range, _) -> i in range }
			?.second
			?: throw NoSuchElementException("no MolInfo for minimized atom index $i")

		coordsByInfo.getOrPut(info) { ArrayList() }.add(Vector3d(pos[0], pos[1], pos[2]))
	}

	// update the info with the minimized coords
	for ((info, coords) in coordsByInfo) {

		// just in case...
		assert(info.minimizableAtoms.size == coords.size)

		info.minimizedCoords = coords
	}
}
