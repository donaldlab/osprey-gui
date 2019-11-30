package edu.duke.cs.ospreygui.compiler

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.tools.associateIdentity


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
	 */
	fun getOrStatic(atom: Atom, fixedAtoms: FixedAtoms): Int =
		this[atom] ?: fixedAtoms.getStatic(atom).let { -it.index - 1 }
}
