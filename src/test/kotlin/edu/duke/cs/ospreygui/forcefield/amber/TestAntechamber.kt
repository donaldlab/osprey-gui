package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.write
import io.kotlintest.shouldBe
import java.nio.file.Paths


class TestAntechamber : SharedSpec({

	group("benzamidine") {

		test("sybyl") {
			val results = Antechamber.run(
				OspreyGui.getResourceAsString("benzamidine.pdb"),
				Antechamber.InType.Pdb,
				Antechamber.AtomTypes.SYBYL
			)
			results.mol2 shouldBe OspreyGui.getResourceAsString("benzamidine.sybyl.mol2")
		}

		test("gaff2") {
			val results = Antechamber.run(
				OspreyGui.getResourceAsString("benzamidine.pdb"),
				Antechamber.InType.Pdb,
				Antechamber.AtomTypes.Gaff2
			)
			results.mol2 shouldBe OspreyGui.getResourceAsString("benzamidine.gaff2.mol2")
		}
	}
})
