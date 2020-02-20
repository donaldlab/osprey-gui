package edu.duke.cs.ospreygui.io

import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
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
})
