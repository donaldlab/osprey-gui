package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.io.ConfLib
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.*
import kotlin.NoSuchElementException


class DesignPosition(
	var name: String,
	val mol: Molecule
) {

	/**
	 * Anchor atoms are used to bond and align conformations to the molecule.
	 * Both the fragment in the conformation library and the design position
	 * should have at least three anchor atoms.
	 */
	val anchorAtoms: MutableList<Atom> = ArrayList()

	/**
	 * Conformations are aligned to the molecule so that both anchor 1 atoms exactly coincide.
	 */
	val anchor1 get() = anchorAtoms.getOrNull(0) ?: throw NoSuchElementException("anchor atom 1 is missing")

	/**
	 * Conformations are aligned to the molecule so that the anchor 1-2 vectors are parallel.
	 */
	val anchor2 get() = anchorAtoms.getOrNull(1) ?: throw NoSuchElementException("anchor atom 2 is missing")

	/**
	 * Conformations are aligned to the molecule so that the anchor 3-1-2 wedges lie in the same plane.
	 */
	val anchor3 get() = anchorAtoms.getOrNull(2) ?: throw NoSuchElementException("anchor atom 3 is missing")

	/**
	 * The atoms that will be replaced by the next mutation, or re-located by the next conformation
	 */
	val atoms: MutableList<Atom> = ArrayList()

	/**
	 * Removes the current atoms from the molecule, including associated bonds.
	 * Then adds the mutated atoms to the molecule, adding bonds and aligning to the anchor as necessary.
	 *
	 * If the molecule is a polymer, the atoms are added to the residue of anchor1.
	 */
	fun setConf(frag: ConfLib.Fragment, conf: ConfLib.Conf) {

		// remove the atoms
		for (atom in atoms) {
			mol.atoms.remove(atom)
			if (mol is Polymer) {
				mol.findResidue(atom)?.atoms?.remove(atom)
			}
		}

		// get the residue of anchor1, if any
		val res = (mol as? Polymer)?.findResidue(anchor1)

		// copy the atoms from the conf and add them to the molecule
		// update the removal atoms with the newly added atoms
		atoms.clear()
		val atomsByInfo = IdentityHashMap<ConfLib.AtomInfo,Atom>()
		for (atomInfo in frag.atoms) {
			val atom = Atom(atomInfo.element, atomInfo.name, Vector3d(conf.coords[atomInfo]))
			atomsByInfo[atomInfo] = atom
			mol.atoms.add(atom)
			res?.atoms?.add(atom)
			atoms.add(atom)
		}

		// add the bonds
		for (bond in frag.bonds) {
			val atoma = atomsByInfo.getValue(bond.a)
			val atomb = atomsByInfo.getValue(bond.b)
			mol.bonds.add(atoma, atomb)
		}

		// add the anchor bonds
		for (bond in frag.anchorBonds) {
			val atom = atomsByInfo.getValue(bond.atom)
			val anchorAtom = anchorAtoms[bond.anchor.id - 1]
			mol.bonds.add(atom, anchorAtom)
		}

		// center coords on anchor 1
		atoms.translate(
			Vector3d(frag.anchor[0].pos)
				.negate()
		)

		// rotate so anchor 1->2 vectors are parallel, and the 3->1->2 wedges lie in the same plane
		atoms.rotate(
			Quaterniond()
				.lookAlong(
					Vector3d(frag.anchor2.pos).sub(frag.anchor1.pos),
					Vector3d(frag.anchor3.pos).sub(frag.anchor1.pos)
				)
				.premul(
					Quaterniond()
						.lookAlong(
							Vector3d(anchor2.pos).sub(anchor1.pos),
							Vector3d(anchor3.pos).sub(anchor1.pos)
						)
						.invert()
				)
		)

		// translate back to anchor 1
		atoms.translate(
			Vector3d(anchor1.pos)
		)
	}

	private fun Iterable<Atom>.translate(t: Vector3d) {
		for (atom in this) {
			atom.pos.add(t)
		}
	}

	private fun Iterable<Atom>.rotate(q: Quaterniond) {
		for (atom in this) {
			atom.pos.rotate(q)
		}
	}
}
