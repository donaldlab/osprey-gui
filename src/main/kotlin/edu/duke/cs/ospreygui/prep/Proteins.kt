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
		DesignPosition(name, res.type, mol).apply {
			setDesignPosition(this, res)
		}

	fun setDesignPosition(pos: DesignPosition, res: Polymer.Residue) = pos.apply {

		// make sure the residue is part of the molecule
		val mol = mol.asPolymer()
		if (mol.chains.none { res in it.residues }) {
			throw IllegalArgumentException("residue $res is not in the molecule for this design position")
		}

		// set the metadata
		name = "${res.id} ${res.type}"
		type = res.type

		// get the backbone atoms
		val resN = res.getProteinAtom("N")
		val resCA = res.getProteinAtom("CA")
		val resC = res.getProteinAtom("C")

		// get the C atom from the previous residue, if any
		val prevC = mol.bonds.bondedAtoms(resN)
			.firstOrNull { it !in res.atoms && it.name == "C" }

		fun Atom.getConnectedAtoms(bbAtoms: Set<Atom>) =
			mol.bfs(
				source = this,
				visitSource = false,
				shouldVisit = { _, dst, _ -> dst !in bbAtoms && dst in res.atoms }
			)
			.map { it.atom }

		// clear previous info
		currentAtoms.clear()
		anchorGroups.clear()

		if (prevC != null) {

			// not N-terminal residue
			val bbAtoms = Atom.identitySetOf(resN, resCA, resC, prevC)

			currentAtoms.apply {

				// add all the non-backbone atoms connected to the anchor atoms
				addAll(resCA.getConnectedAtoms(bbAtoms))
				addAll(resN.getConnectedAtoms(bbAtoms))
			}

			anchorGroups.apply {

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

		} else {

			// N-terminal residue
			val bbAtoms = Atom.identitySetOf(resN, resCA, resC)

			currentAtoms.apply {

				// add all the non-backbone atoms connected to the anchor atoms
				addAll(resCA.getConnectedAtoms(bbAtoms))
			}

			anchorGroups.apply {

				// add just the one single anchor
				add(mutableListOf(
					SingleAnchor(
						a = resCA,
						b = resN,
						c = resC
					)
				))
			}
		}
	}
}

class NotAProteinException(val msg: String) : RuntimeException("Molecule is not a protein: $msg")
