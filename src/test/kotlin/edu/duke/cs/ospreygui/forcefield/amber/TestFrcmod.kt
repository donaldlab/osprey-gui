package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import io.kotlintest.shouldBe


class TestFrcmod : SharedSpec({

	test("benzamidine") {
		val results = Parmchk.run(OspreyGui.getResourceAsString("benzamidine.mol2"))
		results.frcmod shouldBe OspreyGui.getResourceAsString("benzamidine.frcmod")
	}
})
