package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer


object Proteins {

	private fun Molecule.asPolymer() =
		this as? Polymer
			?: throw NotAProteinException("molecule is not a Polymer")

	private fun Polymer.Residue.getProteinAtom(name: String) =
		atoms
			.find { it.name.toLowerCase() == name.toLowerCase() }
			?: throw NotAProteinException("residue does not have atom $name")

	fun makeDesignPosition(mol: Polymer, res: Polymer.Residue, name: String) =
		DesignPosition(name, mol).apply {
			setDesignPosition(this, res)
		}

	fun setDesignPosition(pos: DesignPosition, res: Polymer.Residue) = pos.apply {

		// set the name
		name = "${res.id} ${res.type}"

		// get the previous residue, if any
		val mol = mol.asPolymer()

		// get the backbone atoms
		val resN = res.getProteinAtom("N")
		val resCA = res.getProteinAtom("CA")
		val resC = res.getProteinAtom("C")

		// get the C atom from the previous residue, if any
		val prevC = mol.bonds.bondedAtoms(res.getProteinAtom("N"))
			.firstOrNull { it !in res.atoms && it.name == "C" }
			?: throw UnsupportedOperationException("TODO: support N-terminal residues")

		val bbAtoms = Atom.identitySetOf(resN, resCA, resC, prevC)

		fun Atom.getConnectedAtoms() =
			mol.bfs(
				source = this,
				visitSource = false,
				shouldVisit = { _, dst, _ -> dst !in bbAtoms && dst in res.atoms }
			)
			.map { it.atom }

		anchorGroups.apply {

			// clear any existing anchors
			clear()

			// add the pair of single anchors
			add(mutableListOf(
				DesignPosition.Anchor.Single(
					a = resCA,
					b = resN,
					c = resC
				).apply {
					attachedAtoms.addAll(resCA.getConnectedAtoms())
				},
				DesignPosition.Anchor.Single(
					a = resN,
					b = prevC,
					c = resCA
				).apply {
					attachedAtoms.addAll(resN.getConnectedAtoms())
				}
			))

			// add the double anchor
			add(mutableListOf(
				DesignPosition.Anchor.Double(
					a = resCA,
					b = resN,
					c = prevC,
					d = resC
				).apply {
					attachedAtoms.addAll(resCA.getConnectedAtoms())
					attachedAtoms.addAll(resN.getConnectedAtoms())
				}
			))
		}
	}
}

class NotAProteinException(val msg: String) : RuntimeException("Molecule is not a protein: $msg")
