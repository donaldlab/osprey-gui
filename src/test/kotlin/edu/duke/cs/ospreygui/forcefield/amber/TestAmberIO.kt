package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.fromPDB
import io.kotlintest.shouldBe


class TestAmberIO : SharedSpec({

	test("1CC8") {

		// get just the protein bits from 1CC8
		val mol = (Molecule.fromPDB(OspreyGui.getResourceAsString("1cc8.pdb")) as Polymer)
			.partition()
			.filter { (type, _) -> type in setOf(MoleculeType.Protein) }
			.map { (_, mol) -> mol }
			.combine("1CC8")

		// verify initial sizes
		mol.atoms.size shouldBe 567
		mol.bonds.count() shouldBe 0

		// get the accompanying amber info
		val top = TopIO.read(OspreyGui.getResourceAsString("1cc8.top"))
		val crd = CrdIO.read(OspreyGui.getResourceAsString("1cc8.crd"))

		top.mapTo(mol).apply {
			val numAtomsAdded = addMissingAtoms(crd)
			val numBondsAdded = setBonds()

			numAtomsAdded shouldBe 598
			mol.atoms.size shouldBe 1165

			numBondsAdded shouldBe 391
			mol.bonds.count() shouldBe 391
		}
	}
})
