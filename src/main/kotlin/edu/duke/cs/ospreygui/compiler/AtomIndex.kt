package edu.duke.cs.ospreygui.compiler

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.tools.associateIdentity
import edu.duke.cs.ospreygui.prep.Assignments


/**
 * A convenient collection for a list of atoms,
 * with efficient methods for getting the indices.
 */
class AtomIndex(private val atoms: List<Atom>) : List<Atom> by atoms {

	private val indices = atoms
		.withIndex()
		.associateIdentity { (i, atom) -> atom to i }

	operator fun get(atom: Atom) = indices[atom]

	fun getOrThrow(atom: Atom) =
		get(atom) ?: throw NoSuchElementException("doesn't have atom: $atom")

	/**
	 * If the atom is in this index, return its index.
	 * Otherwise, search the static fixed atoms and encode the index as: -(index - 1).
	 * This way, positive numbers signal atoms in this index,
	 * and negative numbers signal atoms from the fixed static atoms.
	 *
	 * `atom` must come from the assigned molecules, and `fixedAtoms` must come from the A molecules in the assignment atom map.
	 */
	fun getOrStatic(atom: Atom, fixedAtoms: FixedAtoms, assignmentInfo: Assignments.AssignmentInfo): Int =
		this[atom] ?: fixedAtoms.getStatic(assignmentInfo.maps.atoms.getAOrThrow(atom)).let { -it.index - 1 }
}
