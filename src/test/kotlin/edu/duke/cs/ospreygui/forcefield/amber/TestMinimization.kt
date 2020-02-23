package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.fromMol2
import edu.duke.cs.ospreygui.io.fromPDB
import edu.duke.cs.ospreygui.io.withService
import io.kotlintest.shouldBe


class TestMinimization : SharedSpec({

	group("1cc8") {

		test("protein") {
			withService {

				val mol = Molecule.fromMol2(OspreyGui.getResourceAsString("1cc8.protein.h.amber.mol2"))

				val info = MinimizerInfo(mol)
				listOf(info).minimize(10)

				info.minimizedCoords!!.size shouldBe 1165
			}
		}

		test("protein with restrained carbons") {
			withService {

				val mol = Molecule.fromMol2(OspreyGui.getResourceAsString("1cc8.protein.h.amber.mol2"))
				val restrainedAtoms = mol.atoms.filter { it.element == Element.Carbon }

				val info = MinimizerInfo(mol)
				listOf(info).minimize(10, restrainedAtoms)

				info.minimizedCoords!!.size shouldBe 1165
			}
		}

		test("benzamidine") {
			withService {

				val mol = Molecule.fromMol2(OspreyGui.getResourceAsString("benzamidine.h.gaff2.mol2"))

				val info = MinimizerInfo(mol)
				listOf(info).minimize(10)

				info.minimizedCoords!!.size shouldBe 16
			}
		}

		test("benzamidine with all restrained atoms") {
			withService {

				val mol = Molecule.fromMol2(OspreyGui.getResourceAsString("benzamidine.h.gaff2.mol2"))

				val info = MinimizerInfo(mol)
				listOf(info).minimize(10, mol.atoms)

				info.minimizedCoords!!.size shouldBe 16
			}
		}

		test("protein and benzamidine") {
			withService {

				val molProtein = Molecule.fromMol2(OspreyGui.getResourceAsString("1cc8.protein.h.amber.mol2"))
				val molSmall = Molecule.fromMol2(OspreyGui.getResourceAsString("benzamidine.h.gaff2.mol2"))

				val infoProtein = MinimizerInfo(molProtein)
				val infoSmall = MinimizerInfo(molSmall)
				listOf(infoProtein, infoSmall).minimize(10)

				for (info in listOf(infoProtein, infoSmall)) {
					info.minimizedCoords!!.size shouldBe info.minimizableAtoms.size
				}
			}
		}

		test("everything but Mercury") {
			withService {

				val mol = Molecule.fromPDB(OspreyGui.getResourceAsString("1cc8.pdb")) as Polymer

				// remove the mercury atom
				mol.atoms.remove(mol.atoms.find { it.name == "HG" }!!)
				mol.chains.find { it.id == "A" }!!.residues.let { residues ->
					residues.remove(residues.find { it.type == "HG" }!!)
				}

				// add bonds and hydrogens
				for ((a, b) in mol.inferBondsAmber()) {
					mol.bonds.add(a, b)
				}
				for ((heavy, h) in mol.inferProtonation()) {
					mol.atoms.add(h)
					mol.bonds.add(heavy, h)
					mol.findResidueOrThrow(heavy).atoms.add(h)
				}

				val info = MinimizerInfo(mol)
				listOf(info).minimize(10)

				info.minimizedCoords!!.size shouldBe 1165 + 16*2
			}
		}
	}
})
