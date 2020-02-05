package edu.duke.cs.ospreygui.compiler

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.tools.associateIdentity
import java.util.*


/**
 * A place to store all the atoms of the wild-type molecules
 * in a conf space, where each atom is unambiguously assigned
 * to either the static region or a design position.
 *
 * The 'fixed' atoms in a conformation space are the atoms
 * that do not get modified when swiching conformations at design positions.
 *
 * Among the fixed atoms, the 'dyanmic' atoms are the atoms whose
 * forcefield parameters change when switching conformations, since switching
 * conformations can cause the forcefield parameters of nearby atoms to change.
 *
 * The remaining fixed atoms are the 'static' atoms.
 *
 * Dynamic fixed atoms are migrated to the atom lists of the design positions
 * in the compiled conformation space, so their forcefield parameters
 * can be applied correctly after a conformation change.
 *
 * Each static fixed atom is also assigned an index, so it can be efficiently referred to
 * in the compiled conf space.
 */
class FixedAtoms(
	confSpaceIndex: ConfSpaceIndex,
	fixedAtoms: Map<Molecule,List<Atom>>
) {

	// give the fixed atoms mutable collections, so we can move atoms out
	private val fixedAtoms: MutableMap<Molecule,MutableList<Atom>> =
		fixedAtoms.entries.associateIdentity { (mol, atoms) ->
			mol to atoms.toMutableList()
		}

	fun fixed(mol: Molecule): List<Atom> =
		fixedAtoms[mol]
			?: throw NoSuchElementException("no fixed atoms for molecule: $mol")

	fun removeFixed(mol: Molecule, atom: Atom) {
		val atoms = fixedAtoms[mol]
			?: throw NoSuchElementException("no fixed atoms for molecule: $mol")
		val wasRemoved = atoms.remove(atom)
		if (!wasRemoved) {
			throw NoSuchElementException("no fixed atom found for ${atom.fixedName(mol)}")
		}
	}


	data class StaticInfo(
		val mol: Molecule,
		val atom: Atom,
		val index: Int,
		/**
		 * Uniquely describes the atom among all the atoms in the wild-type molecules,
		 * since atoms can come from different molecules
		 */
		val name: String
	)

	private val staticInfos = ArrayList<StaticInfo>()
	private val staticLookup = IdentityHashMap<Atom,StaticInfo>()
	private val _staticAtomsByMol = IdentityHashMap<Molecule,MutableList<Atom>>()

	val statics: List<StaticInfo> get() = staticInfos
	val staticAtomsByMol: Map<Molecule,List<Atom>> get() = _staticAtomsByMol

	fun getStatic(atom: Atom): StaticInfo =
		staticLookup[atom] ?: throw NoSuchElementException("atom $atom is not a static atom")

	/**
	 * Remove all dyanmic atoms from the fixed atoms.
	 * Moves any remaining fixed atoms into the static atoms list.
	 * The fixed atoms list will be empty afterwards.
	 */
	fun updateStatic() {
		for ((mol, atoms) in fixedAtoms) {
			for (atom in atoms) {

				// skip dynamic atoms
				if (posInfos.any { it.isDynamic(atom) }) {
					continue
				}

				// don't add the same atom twice
				if (atom in staticLookup) {
					throw IllegalArgumentException("static atom ${atom.fixedName(mol)} already added")
				}

				// assign an index
				val index = staticInfos.size

				// make the info
				val info = StaticInfo(mol, atom, index, atom.fixedName(mol))
				staticInfos.add(info)
				staticLookup[atom] = info
				_staticAtomsByMol.getOrPut(mol) { ArrayList() }.add(atom)
			}
		}
		fixedAtoms.clear()
	}


	data class DynamicInfo(
		val atom: Atom,
		val index: Int,
		/** the first fragment to claim this atom at this design position */
		val fragInfo: ConfSpaceIndex.FragInfo
	)

	inner class PosInfo(val posInfo: ConfSpaceIndex.PosInfo) {

		private val dynamicInfos = ArrayList<DynamicInfo>()
		private val dynamicLookup = IdentityHashMap<Atom,DynamicInfo>()

		/**
		 * Moves the atom from the fixed atoms list to the dynamic atoms list for this position.
		 * If the atom has already been moved, this function just returns without doing anything.
		 */
		fun addDynamic(atom: Atom, fragInfo: ConfSpaceIndex.FragInfo) {

			// don't add the same atom twice
			if (atom in dynamicLookup) {
				return
			}

			// make sure another position doesn't already have this atom
			for (posInfo in posInfos) {
				posInfo.dynamicLookup[atom]
					?.let { dynamicInfo ->
						throw ClaimedAtomException(dynamicInfo.fragInfo, atom)
					}
			}

			// assign an index
			val index = dynamicInfos.size

			// make the info
			val info = DynamicInfo(atom, index, fragInfo)
			dynamicInfos.add(info)
			dynamicLookup[atom] = info
		}

		fun addDynamic(atoms: Collection<Atom>, fragInfo: ConfSpaceIndex.FragInfo) {
			for (atom in atoms) {
				addDynamic(atom, fragInfo)
			}
		}

		fun isDynamic(atom: Atom): Boolean =
			atom in dynamicLookup

		val dynamics: List<DynamicInfo> get() = dynamicInfos
	}

	private val posInfos: List<PosInfo> =
		confSpaceIndex.positions.map { PosInfo(it) }

	operator fun get(posInfo: ConfSpaceIndex.PosInfo): PosInfo =
		posInfos[posInfo.index]

	class ClaimedAtomException(
		val fragInfo: ConfSpaceIndex.FragInfo,
		val atom: Atom
	) : IllegalArgumentException("position ${fragInfo.posInfo.pos.name} has already claimed dynamic fixed atom ${atom.fixedName(fragInfo.posInfo.pos.mol)}")
}

/**
 * Generate a unique name for the atom,
 * since atoms can come from multiple molecules.
 */
fun Atom.fixedName(mol: Molecule): String {
	val resName = (mol as? Polymer)
		?.let {
			it.findChainAndResidue(this)?.let { (chain, res) ->
				"-${chain.id}${res.id}"
			}
		}
		?: ""
	return "${mol.name}$resName-$name"
}
