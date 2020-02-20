package edu.duke.cs.ospreygui.io

import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreyservice.OspreyService as Server


class TestOspreyService : SharedSpec({

	test("about") {
		Server.use {
			OspreyService.about()
		}
	}
})
