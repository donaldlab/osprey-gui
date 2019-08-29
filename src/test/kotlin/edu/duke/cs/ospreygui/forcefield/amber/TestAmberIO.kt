package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.molecule.combine
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.fromPDB
import io.kotlintest.matchers.beGreaterThan
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe


class TestAmberIO : SharedSpec({

	test("1CC8") {

		// get just the protein bits from 1CC8
		val mol = (Molecule.fromPDB(OspreyGui.getResourceAsString("1cc8.pdb")) as Polymer)
			.partition()
			.filter { (type, _) -> type in setOf(MoleculeType.Protein) }
			.map { (_, mol) -> mol }
			.combine("1CC8").first

		// verify initial sizes
		mol.atoms.size shouldBe 567
		mol.bonds.count() shouldBe 0

		// get the accompanying amber info
		val top = TopIO.read(OspreyGui.getResourceAsString("1cc8.protein.top"))
		val crd = CrdIO.read(OspreyGui.getResourceAsString("1cc8.protein.crd"))

		val numAtomsBefore = mol.atoms.size

		top.mapTo(mol).apply {
			val numAtomsAdded = addMissingAtoms(crd)
			val numBondsAdded = setBonds()

			// we don't want to check that every atom and bond is correct here,
			// but we can check for some simple rules, like:

			// all the atoms should have been added
			mol.atoms.size shouldBe numAtomsBefore + numAtomsAdded

			// all the bonds should have just been added
			mol.bonds.count() shouldBe numBondsAdded

			// we should have hydrogens
			val hydrogens = mol.atoms.filter { it.element == Element.Hydrogen }
			hydrogens.size should beGreaterThan(0)

			// every H atom should have exactly 1 bond to a heavy atom
			for (h in hydrogens) {
				val bondedAtoms = mol.bonds.bondedAtoms(h)
				bondedAtoms.size shouldBe 1
				bondedAtoms.first().element shouldNotBe Element.Hydrogen
			}
		}
	}
})
