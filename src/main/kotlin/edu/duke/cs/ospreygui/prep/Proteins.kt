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

		// make sure the residue is part of the molecule
		val mol = mol.asPolymer()
		if (mol.chains.none { res in it.residues }) {
			throw IllegalArgumentException("residue $res is not in the molecule for this design position")
		}

		// set the name
		name = "${res.id} ${res.type}"

		// get the backbone atoms
		val resN = res.getProteinAtom("N")
		val resCA = res.getProteinAtom("CA")
		val resC = res.getProteinAtom("C")

		// get the C atom from the previous residue, if any
		val prevC = mol.bonds.bondedAtoms(resN)
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

		currentAtoms.apply {

			// clear any existing atoms
			clear()

			// add all the non-backbone atoms connected to the anchor atoms
			addAll(resCA.getConnectedAtoms())
			addAll(resN.getConnectedAtoms())
		}

		anchorGroups.apply {

			// clear any existing anchors
			clear()

			// add the pair of single anchors
			add(mutableListOf(
				SingleAnchor(
					a = resCA,
					b = resN,
					c = resC
				),
				SingleAnchor(
					a = resN,
					b = prevC,
					c = resCA
				)
			))

			// add the double anchor
			add(mutableListOf(
				DoubleAnchor(
					a = resCA,
					b = resN,
					c = prevC,
					d = resC
				)
			))
		}
	}
}

class NotAProteinException(val msg: String) : RuntimeException("Molecule is not a protein: $msg")
