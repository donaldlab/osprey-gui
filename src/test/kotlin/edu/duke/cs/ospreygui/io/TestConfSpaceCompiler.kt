package edu.duke.cs.ospreygui.io

import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.forcefield.Forcefield
import io.kotlintest.shouldBe
import java.nio.file.Paths


class TestConfSpaceCompiler : SharedSpec({

	test("EEF1") {

		ConfSpaceCompiler(makeTestConfSpace()).apply {
			addForcefield(Forcefield.Amber96)
			addForcefield(Forcefield.EEF1)
			compile().run {

				// TEMP
				errors.forEach { println(it) }
				toml?.write(Paths.get("test.ccs.toml"))

				// no errors
				errors.size shouldBe 0
			}
		}
	}
})
