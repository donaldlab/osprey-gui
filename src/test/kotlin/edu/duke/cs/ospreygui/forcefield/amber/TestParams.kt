package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.fromPDB
import io.kotlintest.shouldBe


class TestParams : SharedSpec({

	fun Polymer.findNonTerminalResidue(type: String): Polymer.Residue {
		val residues = chains.first().residues
		return residues.subList(1, residues.size - 1)
			.find { it.type.toLowerCase() == type.toLowerCase() }
			?: throw NoSuchElementException("no non-terminal $type residue")
	}

	fun Polymer.Residue.checkType(types: AmberTypes, atomName: String, atomType: String) {
		val atom = atoms
			.find { it.name == atomName }
			?: throw NoSuchElementException("can't find atom named $atomName in residue $this")
		types.atomTypes[atom] shouldBe atomType
	}

	fun String.skipFirstLine() = substring(indexOf('\n') + 1)

	// pick our forcefields explicitly for these tests
	// so if the default forcefield changes for some reason, these tests don't fail
	val ffnameProtein = ForcefieldName("ff96", Antechamber.AtomTypes.Amber)

	group("1cc8") {

		group("protein") {

			val mol = Molecule.fromPDB(OspreyGui.getResourceAsString("1cc8.protein.pdb")) as Polymer

			// add bonds and hydrogens
			for ((a, b) in mol.inferBondsAmber()) {
				mol.bonds.add(a, b)
			}
			for ((heavy, h) in mol.inferProtonation()) {
				mol.atoms.add(h)
				mol.bonds.add(heavy, h)
				mol.findResidueOrThrow(heavy).atoms.add(h)
			}

			test("types") {

				val types = mol.calcTypesAmber(ffnameProtein)

				// check the atom types against the amber94 atom types for amino acids
				
				mol.findNonTerminalResidue("ARG").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "CT")
					checkType(types, "HG2", "HC")
					checkType(types, "HG3", "HC")
					checkType(types, "CD", "CT")
					checkType(types, "HD2", "H1")
					checkType(types, "HD3", "H1")
					checkType(types, "NE", "N2")
					checkType(types, "HE", "H")
					checkType(types, "CZ", "CA")
					checkType(types, "NH1", "N2")
					checkType(types, "HH11", "H")
					checkType(types, "HH12", "H")
					checkType(types, "NH2", "N2")
					checkType(types, "HH21", "H")
					checkType(types, "HH22", "H")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("HIS").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "CC")
					checkType(types, "ND1", "NB")
					checkType(types, "CE1", "CR")
					checkType(types, "HE1", "H5")
					checkType(types, "NE2", "NA")
					checkType(types, "HE2", "H")
					checkType(types, "CD2", "CW")
					checkType(types, "HD2", "H4")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("LYS").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "CT")
					checkType(types, "HG2", "HC")
					checkType(types, "HG3", "HC")
					checkType(types, "CD", "CT")
					checkType(types, "HD2", "HC")
					checkType(types, "HD3", "HC")
					checkType(types, "CE", "CT")
					checkType(types, "HE2", "HP")
					checkType(types, "HE3", "HP")
					checkType(types, "NZ", "N3")
					checkType(types, "HZ1", "H")
					checkType(types, "HZ2", "H")
					checkType(types, "HZ3", "H")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("ASP").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "C")
					checkType(types, "OD1", "O2")
					checkType(types, "OD2", "O2")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("GLU").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "CT")
					checkType(types, "HG2", "HC")
					checkType(types, "HG3", "HC")
					checkType(types, "CD", "C")
					checkType(types, "OE1", "O2")
					checkType(types, "OE2", "O2")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("CYS").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "H1")
					checkType(types, "HB3", "H1")
					checkType(types, "SG", "SH")
					checkType(types, "HG", "HS")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("GLY").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA2", "H1")
					checkType(types, "HA3", "H1")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("PRO").apply {
					checkType(types, "N", "N")
					checkType(types, "CD", "CT")
					checkType(types, "HD2", "H1")
					checkType(types, "HD3", "H1")
					checkType(types, "CG", "CT")
					checkType(types, "HG2", "HC")
					checkType(types, "HG3", "HC")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("ALA").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB1", "HC")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("VAL").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB", "HC")
					checkType(types, "CG1", "CT")
					checkType(types, "HG11", "HC")
					checkType(types, "HG12", "HC")
					checkType(types, "HG13", "HC")
					checkType(types, "CG2", "CT")
					checkType(types, "HG21", "HC")
					checkType(types, "HG22", "HC")
					checkType(types, "HG23", "HC")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("ILE").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB", "HC")
					checkType(types, "CG2", "CT")
					checkType(types, "HG21", "HC")
					checkType(types, "HG22", "HC")
					checkType(types, "HG23", "HC")
					checkType(types, "CG1", "CT")
					checkType(types, "HG12", "HC")
					checkType(types, "HG13", "HC")
					checkType(types, "CD1", "CT")
					checkType(types, "HD11", "HC")
					checkType(types, "HD12", "HC")
					checkType(types, "HD13", "HC")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("LEU").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "CT")
					checkType(types, "HG", "HC")
					checkType(types, "CD1", "CT")
					checkType(types, "HD11", "HC")
					checkType(types, "HD12", "HC")
					checkType(types, "HD13", "HC")
					checkType(types, "CD2", "CT")
					checkType(types, "HD21", "HC")
					checkType(types, "HD22", "HC")
					checkType(types, "HD23", "HC")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("MET").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "CT")
					checkType(types, "HG2", "H1")
					checkType(types, "HG3", "H1")
					checkType(types, "SD", "S")
					checkType(types, "CE", "CT")
					checkType(types, "HE1", "H1")
					checkType(types, "HE2", "H1")
					checkType(types, "HE3", "H1")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("PHE").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "CA")
					checkType(types, "CD1", "CA")
					checkType(types, "HD1", "HA")
					checkType(types, "CE1", "CA")
					checkType(types, "HE1", "HA")
					checkType(types, "CZ", "CA")
					checkType(types, "HZ", "HA")
					checkType(types, "CE2", "CA")
					checkType(types, "HE2", "HA")
					checkType(types, "CD2", "CA")
					checkType(types, "HD2", "HA")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("TYR").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "CA")
					checkType(types, "CD1", "CA")
					checkType(types, "HD1", "HA")
					checkType(types, "CE1", "CA")
					checkType(types, "HE1", "HA")
					checkType(types, "CZ", "C")
					checkType(types, "OH", "OH")
					checkType(types, "HH", "HO")
					checkType(types, "CE2", "CA")
					checkType(types, "HE2", "HA")
					checkType(types, "CD2", "CA")
					checkType(types, "HD2", "HA")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				/* 1cc8 doesn't have TRP =(
				mol.findNonTerminalResidue("TRP").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "C*")
					checkType(types, "CD1", "CW")
					checkType(types, "HD1", "H4")
					checkType(types, "NE1", "NA")
					checkType(types, "HE1", "H")
					checkType(types, "CE2", "CN")
					checkType(types, "CZ2", "CA")
					checkType(types, "HZ2", "HA")
					checkType(types, "CH2", "CA")
					checkType(types, "HH2", "HA")
					checkType(types, "CZ3", "CA")
					checkType(types, "HZ3", "HA")
					checkType(types, "CE3", "CA")
					checkType(types, "HE3", "HA")
					checkType(types, "CD2", "CB")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}
				*/
				// TODO: find a protein that has TRPs?

				mol.findNonTerminalResidue("SER").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "H1")
					checkType(types, "HB3", "H1")
					checkType(types, "OG", "OH")
					checkType(types, "HG", "HO")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("THR").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB", "H1")
					checkType(types, "CG2", "CT")
					checkType(types, "HG21", "HC")
					checkType(types, "HG22", "HC")
					checkType(types, "HG23", "HC")
					checkType(types, "OG1", "OH")
					checkType(types, "HG1", "HO")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("ASN").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "C")
					checkType(types, "OD1", "O")
					checkType(types, "ND2", "N")
					checkType(types, "HD21", "H")
					checkType(types, "HD22", "H")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}

				mol.findNonTerminalResidue("GLN").apply {
					checkType(types, "N", "N")
					checkType(types, "H", "H")
					checkType(types, "CA", "CT")
					checkType(types, "HA", "H1")
					checkType(types, "CB", "CT")
					checkType(types, "HB2", "HC")
					checkType(types, "HB3", "HC")
					checkType(types, "CG", "CT")
					checkType(types, "HG2", "HC")
					checkType(types, "HG3", "HC")
					checkType(types, "CD", "C")
					checkType(types, "OE1", "O")
					checkType(types, "NE2", "N")
					checkType(types, "HE21", "H")
					checkType(types, "HE22", "H")
					checkType(types, "C", "C")
					checkType(types, "O", "O")
				}
			}

			test("params") {

				val types = mol.calcTypesAmber(ffnameProtein)
				val params = mol.calcParamsAmber(types)

				// make sure the results still match what we expect
				// (skip the first line of the topology file, since it has a timestamp)
				params.top.skipFirstLine() shouldBe OspreyGui.getResourceAsString("1cc8.protein.top").skipFirstLine()
				params.crd shouldBe OspreyGui.getResourceAsString("1cc8.protein.crd")
			}
		}
	}
})
