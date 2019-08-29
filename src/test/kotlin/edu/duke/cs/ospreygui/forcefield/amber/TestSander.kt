package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.fromPDB
import io.kotlintest.shouldBe


class TestSander : SharedSpec({

	// pick our forcefields explicitly for these tests
	// so if the default forcefield changes for some reason, these tests don't fail
	val ffnameProtein = ForcefieldName("ff96", Antechamber.AtomTypes.Amber)

	group("1cc8") {

		test("protein") {

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

			// get params
			val types = mol.calcTypesAmber(ffnameProtein)
			val params = mol.calcParamsAmber(types)

			// minimize!
			val results = Sander.minimize(
				params.top,
				params.crd,
				numCycles = 10
			)

			// all the atoms should have new coords
			results.coords.size shouldBe mol.atoms.size
		}
	}
})
