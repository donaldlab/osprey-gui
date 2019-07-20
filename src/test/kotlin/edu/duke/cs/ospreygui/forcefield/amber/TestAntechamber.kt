package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.tempFolder
import io.kotlintest.shouldBe


class TestAntechamber : SharedSpec({

	test("benzamidine") {

		val pdb = OspreyGui.getResourceAsString("benzamidine.pdb")

		tempFolder("antechamber") { cwd ->
			val results = Antechamber.run(cwd, pdb)
			results.mol2 shouldBe OspreyGui.getResourceAsString("benzamidine.mol2")
		}
	}
})
