package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.tempFolder
import io.kotlintest.shouldBe


class TestFrcmod : SharedSpec({

	test("benzamidine") {

		val mol2 = OspreyGui.getResourceAsString("benzamidine.mol2")

		tempFolder("parmchk") { cwd ->
			val results = Parmchk.run(cwd, mol2)
			results.frcmod shouldBe OspreyGui.getResourceAsString("benzamidine.frcmod")
		}
	}
})
