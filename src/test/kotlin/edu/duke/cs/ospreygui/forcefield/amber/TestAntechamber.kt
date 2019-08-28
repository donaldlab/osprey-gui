package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import io.kotlintest.shouldBe


class TestAntechamber : SharedSpec({

	test("benzamidine") {
		val results = Antechamber.run(
			OspreyGui.getResourceAsString("benzamidine.pdb"),
			Antechamber.InType.Pdb,
			Antechamber.AtomTypes.SYBYL
		)
		results.mol2 shouldBe OspreyGui.getResourceAsString("benzamidine.mol2")
	}
})
