package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.OspreyService
import edu.duke.cs.ospreygui.io.withService
import edu.duke.cs.ospreyservice.services.MoleculeFFInfoRequest
import io.kotlintest.shouldBe


class TestForcefieldInfo : SharedSpec({

	group("benzamidine") {

		test("heavy") {
			withService {

				val results = OspreyService.moleculeFFInfo(MoleculeFFInfoRequest(
					mol2 = OspreyGui.getResourceAsString("benzamidine.gaff2.mol2"),
					ffname = "gaff2"
				))

				results.ffinfo shouldBe OspreyGui.getResourceAsString("benzamidine.frcmod")
			}
		}

		test("with hydrogens") {
			withService {

				val results = OspreyService.moleculeFFInfo(MoleculeFFInfoRequest(
					mol2 = OspreyGui.getResourceAsString("benzamidine.h.gaff2.mol2"),
					ffname = "gaff2"
				))

				results.ffinfo shouldBe OspreyGui.getResourceAsString("benzamidine.h.frcmod")
			}
		}
	}
})
