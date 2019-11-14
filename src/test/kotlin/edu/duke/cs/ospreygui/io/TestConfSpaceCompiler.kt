package edu.duke.cs.ospreygui.io

import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.forcefield.Forcefield


class TestConfSpaceCompiler : SharedSpec({

	test("EEF1") {

		ConfSpaceCompiler(makeTestConfSpace()).apply {
			addForcefield(Forcefield.EEF1)
			compile().run {
				// TEMP
				toml!!.lines().withIndex().forEach { (i, line) -> println("%6d: %s".format(i + 1, line)) }
			}
		}
	}
})
