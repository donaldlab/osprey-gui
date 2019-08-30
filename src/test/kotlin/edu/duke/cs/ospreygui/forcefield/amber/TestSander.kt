package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import io.kotlintest.shouldBe


class TestSander : SharedSpec({

	group("1cc8") {

		test("protein") {

			val params = AmberParams(
				OspreyGui.getResourceAsString("1cc8.protein.top"),
				OspreyGui.getResourceAsString("1cc8.protein.crd")
			)

			// minimize!
			val results = Sander.minimize(
				params.top,
				params.crd,
				numCycles = 10
			)

			// all the atoms should have new coords
			results.coords.size shouldBe 1165
		}

		test("benzamidine") {

			val params = AmberParams(
				OspreyGui.getResourceAsString("benzamidine.top"),
				OspreyGui.getResourceAsString("benzamidine.crd")
			)

			val results = Sander.minimize(
				params.top,
				params.crd,
				numCycles = 10
			)

			results.coords.size shouldBe 16
		}

		test("protein and benzamidine") {

			val params = AmberParams(
				OspreyGui.getResourceAsString("1cc8.protein.benzamidine.top"),
				OspreyGui.getResourceAsString("1cc8.protein.benzamidine.crd")
			)

			val results = Sander.minimize(
				params.top,
				params.crd,
				numCycles = 10
			)

			results.coords.size shouldBe 1165 + 16
		}
	}
})
