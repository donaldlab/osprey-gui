package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.fromMol2
import edu.duke.cs.ospreygui.io.fromPDB
import io.kotlintest.shouldBe


class TestBonds : SharedSpec({

	fun Molecule.Bonds.toContentSet() = toSet().map { it.toContent() }.toSet()

	test("benzamidine") {

		val mol = Molecule.fromPDB(OspreyGui.getResourceAsString("benzamidine.pdb"))

		for ((a1, a2) in mol.inferBondsAmber()) {
			mol.bonds.add(a1, a2)
		}

		val bondedMol = Molecule.fromMol2(OspreyGui.getResourceAsString("benzamidine.mol2"))
		mol.bonds.toContentSet() shouldBe bondedMol.bonds.toContentSet()
	}

	test("1cc8") {

		val mol = Molecule.fromPDB(OspreyGui.getResourceAsString("1cc8.protein.pdb"))

		for ((a1, a2) in mol.inferBondsAmber()) {
			mol.bonds.add(a1, a2)
		}

		val bondedMol = Molecule.fromMol2(OspreyGui.getResourceAsString("1cc8.protein.mol2"))
		mol.bonds.toContentSet() shouldBe bondedMol.bonds.toContentSet()
	}
})
