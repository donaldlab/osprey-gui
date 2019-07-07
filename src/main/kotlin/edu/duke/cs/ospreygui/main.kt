package edu.duke.cs.ospreygui

import cuchaz.kludge.tools.autoCloser
import edu.duke.cs.molscope.gui.Window
import edu.duke.cs.molscope.gui.features.win.AboutMolscope
import edu.duke.cs.molscope.gui.features.win.Exit
import edu.duke.cs.molscope.gui.features.win.MenuColorsMode
import edu.duke.cs.ospreygui.features.win.AboutOspreyGui
import edu.duke.cs.ospreygui.features.win.ImportPDB
import edu.duke.cs.ospreygui.features.win.OpenOMOL


fun main() = autoCloser {

	// open a window
	val win = Window(
		width = 1280, // TODO: make this bigger?
		height = 720,
		title = "OSPREY"
	).autoClose()

	// add window features
	win.features.run {
		menu("File") {
			add(ImportPDB())
			add(OpenOMOL())
			addSeparator()
			add(Exit())
		}
		menu("View") {
			add(MenuColorsMode())
		}
		menu("Help") {
			// TODO: about osprey?
			add(AboutOspreyGui())
			add(AboutMolscope())
		}
	}

	win.waitForClose()

} // end of scope here cleans up all autoClose() resources
