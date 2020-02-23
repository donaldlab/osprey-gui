package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.fromMol2
import edu.duke.cs.ospreygui.io.fromMol2WithMetadata
import edu.duke.cs.ospreygui.io.withService
import io.kotlintest.shouldBe


class TestParams : SharedSpec({

	fun Polymer.findNonTerminalResidue(type: String): Polymer.Residue {
		val residues = chains.first().residues
		return residues.subList(1, residues.size - 1)
			.find { it.type.toLowerCase() == type.toLowerCase() }
			?: throw NoSuchElementException("no non-terminal $type residue")
	}

	fun Collection<Atom>.assertType(types: AmberTypes, atomName: String, atomType: String) {
		val atom = find { it.name == atomName }
			?: throw NoSuchElementException("can't find atom named $atomName in residue $this")
		types.atomTypes[atom] shouldBe atomType
	}

	fun String.skipFirstLine() = substring(indexOf('\n') + 1)

	fun AmberParams.assert(top: String, crd: String) {
		// skip the first line of the topology file, since it has a timestamp
		this.top.skipFirstLine() shouldBe top.skipFirstLine()
		this.crd shouldBe crd
	}

	group("1cc8") {

		group("protein") {

			val mol = Molecule.fromMol2(OspreyGui.getResourceAsString("1cc8.protein.h.amber.mol2")) as Polymer

			test("types") {
				withService {

					val types = mol.calcTypesAmber(MoleculeType.Protein, ForcefieldName.ff96)

					// check the atom types against the amber94 atom types for amino acids

					mol.findNonTerminalResidue("ARG").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CG", "CT")
						atoms.assertType(types, "HG2", "HC")
						atoms.assertType(types, "HG3", "HC")
						atoms.assertType(types, "CD", "CT")
						atoms.assertType(types, "HD2", "H1")
						atoms.assertType(types, "HD3", "H1")
						atoms.assertType(types, "NE", "N2")
						atoms.assertType(types, "HE", "H")
						atoms.assertType(types, "CZ", "CA")
						atoms.assertType(types, "NH1", "N2")
						atoms.assertType(types, "HH11", "H")
						atoms.assertType(types, "HH12", "H")
						atoms.assertType(types, "NH2", "N2")
						atoms.assertType(types, "HH21", "H")
						atoms.assertType(types, "HH22", "H")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("HIS").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CG", "CC")
						atoms.assertType(types, "ND1", "NB")
						atoms.assertType(types, "CE1", "CR")
						atoms.assertType(types, "HE1", "H5")
						atoms.assertType(types, "NE2", "NA")
						atoms.assertType(types, "HE2", "H")
						atoms.assertType(types, "CD2", "CW")
						atoms.assertType(types, "HD2", "H4")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("LYS").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CG", "CT")
						atoms.assertType(types, "HG2", "HC")
						atoms.assertType(types, "HG3", "HC")
						atoms.assertType(types, "CD", "CT")
						atoms.assertType(types, "HD2", "HC")
						atoms.assertType(types, "HD3", "HC")
						atoms.assertType(types, "CE", "CT")
						atoms.assertType(types, "HE2", "HP")
						atoms.assertType(types, "HE3", "HP")
						atoms.assertType(types, "NZ", "N3")
						atoms.assertType(types, "HZ1", "H")
						atoms.assertType(types, "HZ2", "H")
						atoms.assertType(types, "HZ3", "H")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("ASP").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CG", "C")
						atoms.assertType(types, "OD1", "O2")
						atoms.assertType(types, "OD2", "O2")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("GLU").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CG", "CT")
						atoms.assertType(types, "HG2", "HC")
						atoms.assertType(types, "HG3", "HC")
						atoms.assertType(types, "CD", "C")
						atoms.assertType(types, "OE1", "O2")
						atoms.assertType(types, "OE2", "O2")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("CYS").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "H1")
						atoms.assertType(types, "HB3", "H1")
						atoms.assertType(types, "SG", "SH")
						atoms.assertType(types, "HG", "HS")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("GLY").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA2", "H1")
						atoms.assertType(types, "HA3", "H1")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("PRO").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "CD", "CT")
						atoms.assertType(types, "HD2", "H1")
						atoms.assertType(types, "HD3", "H1")
						atoms.assertType(types, "CG", "CT")
						atoms.assertType(types, "HG2", "HC")
						atoms.assertType(types, "HG3", "HC")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("ALA").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB1", "HC")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("VAL").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB", "HC")
						atoms.assertType(types, "CG1", "CT")
						atoms.assertType(types, "HG11", "HC")
						atoms.assertType(types, "HG12", "HC")
						atoms.assertType(types, "HG13", "HC")
						atoms.assertType(types, "CG2", "CT")
						atoms.assertType(types, "HG21", "HC")
						atoms.assertType(types, "HG22", "HC")
						atoms.assertType(types, "HG23", "HC")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("ILE").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB", "HC")
						atoms.assertType(types, "CG2", "CT")
						atoms.assertType(types, "HG21", "HC")
						atoms.assertType(types, "HG22", "HC")
						atoms.assertType(types, "HG23", "HC")
						atoms.assertType(types, "CG1", "CT")
						atoms.assertType(types, "HG12", "HC")
						atoms.assertType(types, "HG13", "HC")
						atoms.assertType(types, "CD1", "CT")
						atoms.assertType(types, "HD11", "HC")
						atoms.assertType(types, "HD12", "HC")
						atoms.assertType(types, "HD13", "HC")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("LEU").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CG", "CT")
						atoms.assertType(types, "HG", "HC")
						atoms.assertType(types, "CD1", "CT")
						atoms.assertType(types, "HD11", "HC")
						atoms.assertType(types, "HD12", "HC")
						atoms.assertType(types, "HD13", "HC")
						atoms.assertType(types, "CD2", "CT")
						atoms.assertType(types, "HD21", "HC")
						atoms.assertType(types, "HD22", "HC")
						atoms.assertType(types, "HD23", "HC")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("MET").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CG", "CT")
						atoms.assertType(types, "HG2", "H1")
						atoms.assertType(types, "HG3", "H1")
						atoms.assertType(types, "SD", "S")
						atoms.assertType(types, "CE", "CT")
						atoms.assertType(types, "HE1", "H1")
						atoms.assertType(types, "HE2", "H1")
						atoms.assertType(types, "HE3", "H1")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("PHE").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CG", "CA")
						atoms.assertType(types, "CD1", "CA")
						atoms.assertType(types, "HD1", "HA")
						atoms.assertType(types, "CE1", "CA")
						atoms.assertType(types, "HE1", "HA")
						atoms.assertType(types, "CZ", "CA")
						atoms.assertType(types, "HZ", "HA")
						atoms.assertType(types, "CE2", "CA")
						atoms.assertType(types, "HE2", "HA")
						atoms.assertType(types, "CD2", "CA")
						atoms.assertType(types, "HD2", "HA")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("TYR").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CG", "CA")
						atoms.assertType(types, "CD1", "CA")
						atoms.assertType(types, "HD1", "HA")
						atoms.assertType(types, "CE1", "CA")
						atoms.assertType(types, "HE1", "HA")
						atoms.assertType(types, "CZ", "C")
						atoms.assertType(types, "OH", "OH")
						atoms.assertType(types, "HH", "HO")
						atoms.assertType(types, "CE2", "CA")
						atoms.assertType(types, "HE2", "HA")
						atoms.assertType(types, "CD2", "CA")
						atoms.assertType(types, "HD2", "HA")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					/* 1cc8 doesn't have TRP =(
					mol.findNonTerminalResidue("TRP").apply {
						atoms.checkType(types, "N", "N")
						atoms.checkType(types, "H", "H")
						atoms.checkType(types, "CA", "CT")
						atoms.checkType(types, "HA", "H1")
						atoms.checkType(types, "CB", "CT")
						atoms.checkType(types, "HB2", "HC")
						atoms.checkType(types, "HB3", "HC")
						atoms.checkType(types, "CG", "C*")
						atoms.checkType(types, "CD1", "CW")
						atoms.checkType(types, "HD1", "H4")
						atoms.checkType(types, "NE1", "NA")
						atoms.checkType(types, "HE1", "H")
						atoms.checkType(types, "CE2", "CN")
						atoms.checkType(types, "CZ2", "CA")
						atoms.checkType(types, "HZ2", "HA")
						atoms.checkType(types, "CH2", "CA")
						atoms.checkType(types, "HH2", "HA")
						atoms.checkType(types, "CZ3", "CA")
						atoms.checkType(types, "HZ3", "HA")
						atoms.checkType(types, "CE3", "CA")
						atoms.checkType(types, "HE3", "HA")
						atoms.checkType(types, "CD2", "CB")
						atoms.checkType(types, "C", "C")
						atoms.checkType(types, "O", "O")
					}
					*/
					// TODO: find a protein that has TRPs?

					mol.findNonTerminalResidue("SER").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "H1")
						atoms.assertType(types, "HB3", "H1")
						atoms.assertType(types, "OG", "OH")
						atoms.assertType(types, "HG", "HO")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("THR").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB", "H1")
						atoms.assertType(types, "CG2", "CT")
						atoms.assertType(types, "HG21", "HC")
						atoms.assertType(types, "HG22", "HC")
						atoms.assertType(types, "HG23", "HC")
						atoms.assertType(types, "OG1", "OH")
						atoms.assertType(types, "HG1", "HO")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("ASN").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CG", "C")
						atoms.assertType(types, "OD1", "O")
						atoms.assertType(types, "ND2", "N")
						atoms.assertType(types, "HD21", "H")
						atoms.assertType(types, "HD22", "H")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}

					mol.findNonTerminalResidue("GLN").apply {
						atoms.assertType(types, "N", "N")
						atoms.assertType(types, "H", "H")
						atoms.assertType(types, "CA", "CT")
						atoms.assertType(types, "HA", "H1")
						atoms.assertType(types, "CB", "CT")
						atoms.assertType(types, "HB2", "HC")
						atoms.assertType(types, "HB3", "HC")
						atoms.assertType(types, "CG", "CT")
						atoms.assertType(types, "HG2", "HC")
						atoms.assertType(types, "HG3", "HC")
						atoms.assertType(types, "CD", "C")
						atoms.assertType(types, "OE1", "O")
						atoms.assertType(types, "NE2", "N")
						atoms.assertType(types, "HE21", "H")
						atoms.assertType(types, "HE22", "H")
						atoms.assertType(types, "C", "C")
						atoms.assertType(types, "O", "O")
					}
				}
			}

			test("mods") {
				withService {

					val types = mol.calcTypesAmber(MoleculeType.Protein, ForcefieldName.ff96)
					val frcmod = mol.calcModsAmber(types)

					frcmod shouldBe null
				}
			}

			test("params") {
				withService {

					val types = mol.calcTypesAmber(MoleculeType.Protein, ForcefieldName.ff96)
					val params = AmberMolParams(mol, types, null).calcParamsAmber()

					params.assert(
						OspreyGui.getResourceAsString("1cc8.protein.top"),
						OspreyGui.getResourceAsString("1cc8.protein.crd")
					)
				}
			}
		}

		group("benzamidine") {

			val (mol, metadata) = Molecule.fromMol2WithMetadata(OspreyGui.getResourceAsString("benzamidine.h.gaff2.mol2"))

			test("types") {
				withService {

					val types = mol.calcTypesAmber(MoleculeType.SmallMolecule, ForcefieldName.gaff2)

					mol.atoms.assertType(types, "C1", "ca")
					mol.atoms.assertType(types, "C2", "ca")
					mol.atoms.assertType(types, "C3", "ca")
					mol.atoms.assertType(types, "C4", "ca")
					mol.atoms.assertType(types, "C5", "ca")
					mol.atoms.assertType(types, "C6", "ca")
					mol.atoms.assertType(types, "C", "ce")
					mol.atoms.assertType(types, "N1", "n2")
					mol.atoms.assertType(types, "N2", "n2")
					mol.atoms.assertType(types, "H10", "ha")
					mol.atoms.assertType(types, "H11", "ha")
					mol.atoms.assertType(types, "H12", "ha")
					mol.atoms.assertType(types, "H13", "ha")
					mol.atoms.assertType(types, "H14", "ha")
					mol.atoms.assertType(types, "H15", "hn")
					mol.atoms.assertType(types, "H16", "hn")
				}
			}

			test("mods") {
				withService {

					val types = AmberTypes(ForcefieldName.gaff2, metadata)
					val frcmod = mol.calcModsAmber(types)

					frcmod shouldBe OspreyGui.getResourceAsString("benzamidine.h.frcmod")
				}
			}

			test("params") {
				withService {

					val types = AmberTypes(ForcefieldName.gaff2, metadata)
					val frcmod = OspreyGui.getResourceAsString("benzamidine.h.frcmod")
					val params = AmberMolParams(mol, types, frcmod).calcParamsAmber()

					params.assert(
						OspreyGui.getResourceAsString("benzamidine.top"),
						OspreyGui.getResourceAsString("benzamidine.crd")
					)
				}
			}
		}

		group("protein and benzamidine") {

			val (molProtein, metadataProtein) = Molecule.fromMol2WithMetadata(OspreyGui.getResourceAsString("1cc8.protein.h.amber.mol2"))
			val (molSmall, metadataSmall) = Molecule.fromMol2WithMetadata(OspreyGui.getResourceAsString("benzamidine.h.gaff2.mol2"))

			val typesProtein = AmberTypes(ForcefieldName.ff96, metadataProtein)
			val typesSmall = AmberTypes(ForcefieldName.gaff2, metadataSmall)

			test("types") {

				// spot check a few of the types
				molProtein as Polymer
				molProtein.findNonTerminalResidue("THR").apply {
					atoms.assertType(typesProtein, "N", "N")
					atoms.assertType(typesProtein, "CA", "CT")
					atoms.assertType(typesProtein, "HG23", "HC")
				}

				molSmall.atoms.assertType(typesSmall, "C6", "ca")
				molSmall.atoms.assertType(typesSmall, "N1", "n2")
				molSmall.atoms.assertType(typesSmall, "N2", "n2")
			}

			test("params") {
				withService {

					val params = listOf(
						AmberMolParams(molProtein, typesProtein, null),
						AmberMolParams(molSmall, typesSmall, OspreyGui.getResourceAsString("benzamidine.h.frcmod"))
					).calcParamsAmber()

					params.assert(
						OspreyGui.getResourceAsString("1cc8.protein.benzamidine.top"),
						OspreyGui.getResourceAsString("1cc8.protein.benzamidine.crd")
					)
				}
			}
		}
	}
})
