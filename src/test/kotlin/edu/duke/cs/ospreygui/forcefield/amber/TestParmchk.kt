package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import io.kotlintest.shouldBe


class TestParmchk : SharedSpec({

	test("benzamidine") {
		val results = Parmchk.run(
			OspreyGui.getResourceAsString("benzamidine.gaff2.mol2"),
			Parmchk.AtomTypes.Gaff2
		)
		results.frcmod shouldBe OspreyGui.getResourceAsString("benzamidine.frcmod")
	}
})
