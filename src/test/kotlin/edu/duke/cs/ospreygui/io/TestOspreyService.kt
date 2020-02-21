package edu.duke.cs.ospreygui.io

import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreyservice.services.BondsRequest
import edu.duke.cs.ospreyservice.services.MissingAtomsRequest
import java.nio.file.Paths
import edu.duke.cs.ospreyservice.OspreyService as Server


class TestOspreyService : SharedSpec({

	fun testService(name: String, block: () -> Unit) {
		test(name) {
			Server.Instance(Paths.get("../osprey-service"), wait = false).use {
				block()
			}
		}
	}

	testService("about") {
		OspreyService.about()
	}

	testService("missingAtoms") {
		val pdb = OspreyGui.getResourceAsString("1cc8.protein.pdb")
		val type = MoleculeType.Protein
		OspreyService.missingAtoms(MissingAtomsRequest(pdb, type.defaultForcefieldNameOrThrow.name))
	}

	group("bonds") {

		testService("protein") {
			val pdb = OspreyGui.getResourceAsString("1cc8.protein.pdb")
			val type = MoleculeType.Protein
			OspreyService.bonds(BondsRequest(pdb, type.defaultForcefieldNameOrThrow.name))
		}

		testService("small mol") {
			val pdb = OspreyGui.getResourceAsString("benzamidine.pdb")
			OspreyService.bonds(BondsRequest(pdb, null))
		}
	}
})
