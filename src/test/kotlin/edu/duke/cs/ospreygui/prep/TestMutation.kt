package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.io.fromOMOL
import io.kotlintest.matchers.beLessThanOrEqualTo
import io.kotlintest.should
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.*


class TestMutation : SharedSpec({

	group("1CC8") {

		val conflib = ConfLib.from(OspreyGui.getResourceAsString("conflib/lovell.conflib.toml"))
		val protein1cc8 = Molecule.fromOMOL(OspreyGui.getResourceAsString("1cc8.protein.omol.toml"))[0] as Polymer

		// pick gly 17 (aribtrarily) to use as a mutable position
		fun Polymer.gly17() =
			findChainOrThrow("A").findResidueOrThrow("17")

		val res1cc8 = protein1cc8.gly17()

		data class Instance(
			val mol: Polymer,
			val res: Polymer.Residue,
			val pos: DesignPosition
		)
		fun instance(): Instance {

			// copy the molecule, so we don't destroy the original
			val mol = protein1cc8.copy()

			val res = mol.gly17()
			val pos = DesignPosition("pos1", mol).apply {

				removalAtoms.apply {
					add(res.findAtomOrThrow("HA2"))
					add(res.findAtomOrThrow("HA3"))
				}
				anchorAtoms.apply {
					add(res.findAtomOrThrow("CA"))
					add(res.findAtomOrThrow("N"))
					add(res.findAtomOrThrow("C"))
				}
			}

			return Instance(mol, res, pos)
		}

		test("glycine") {

			val (mol, res, pos) = instance()

			// find the glycine conformation in the library
			val frag = conflib.fragments.getValue("GLY")
			val conf = frag.confs.getValue("GLY")

			// TODO: refactor this code to be re-usable

			// remove the existing atoms
			for (atom in pos.removalAtoms) {
				mol.atoms.remove(atom)
				res.atoms.remove(atom)
			}

			// copy the atoms from the conf and add them to the molecule
			val atomsByInfo = IdentityHashMap<ConfLib.AtomInfo,Atom>()
			for (atomInfo in frag.atoms) {
				val atom = Atom(atomInfo.element, atomInfo.name, Vector3d(conf.coords[atomInfo]))
				atomsByInfo[atomInfo] = atom
				mol.atoms.add(atom)
				res.atoms.add(atom)
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
				val anchorAtom = pos.anchorAtoms[bond.anchor.id - 1]
				mol.bonds.add(atom, anchorAtom)
			}

			// align the coords using the anchor
			val atoms = atomsByInfo.values
			fun Vector3d.translateAtoms() {
				for (atom in atoms) {
					atom.pos.add(this)
				}
			}
			fun Quaterniond.rotateAtoms() {
				for (atom in atoms) {
					atom.pos.rotate(this)
				}
			}

			// center coords on anchor 1
			Vector3d(frag.anchor[0].pos)
				.negate()
				.translateAtoms()

			// rotate so anchor 1->2 vectors are parallel, and the 3->1->2 wedges lie in the same plane
			val anchor12src = Vector3d(frag.anchor2.pos).sub(frag.anchor1.pos)
			val anchor13src = Vector3d(frag.anchor3.pos).sub(frag.anchor1.pos)
			Quaterniond()
				.lookAlong(anchor12src, anchor13src)
				.rotateAtoms()
			val anchor12dst = Vector3d(pos.anchor2.pos).sub(pos.anchor1.pos)
			val anchor13dst = Vector3d(pos.anchor3.pos).sub(pos.anchor1.pos)
			Quaterniond()
				.lookAlong(anchor12dst, anchor13dst)
				.invert()
				.rotateAtoms()

			// translate back to anchor 1
			Vector3d(pos.anchor1.pos)
				.translateAtoms()

			// does this look like glycine?
			for (atomName in listOf("N", "CA", "C", "HA2", "HA3")) {
				val atom = res.findAtomOrThrow(atomName)
				val atom1cc8 = res1cc8.findAtomOrThrow(atomName)
				atom.pos.distance(atom1cc8.pos) should beLessThanOrEqualTo(0.1)
			}
		}
	}
})
