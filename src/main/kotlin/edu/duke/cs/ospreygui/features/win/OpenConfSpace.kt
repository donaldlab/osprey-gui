package edu.duke.cs.ospreygui.features.win

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.WindowFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.io.fromToml
import edu.duke.cs.ospreygui.io.read
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.prep.ConfSpacePrep
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*


class OpenConfSpace : WindowFeature {

	override val id = FeatureId("open.confspace")

	val filterList = FilterList(listOf("confspace.toml"))
	var dir = Paths.get("").toAbsolutePath()

	override fun menu(imgui: Commands, win: WindowCommands) = imgui.run {
		if (menuItem("Open Conformation Space")) {
			FileDialog.openFile(filterList, dir)?.let { path ->
				dir = path.parent
				open(win, path)
			}
		}
	}

	private fun open(win: WindowCommands, path: Path) = win.showExceptions {

		// resume a previous prep
		val confSpace = ConfSpace.fromToml(
			path.read()
		)
		ConfSpacePrep(win, confSpace).apply {

			// build an ad-hoc conf lib from the fragments in the conf space
			// so the GUI can use them
			conflibs.add(ConfLib(
				name = "Conformation Space",
				fragments = confSpace.libraryFragments()
					.associateByTo(IdentityHashMap()) { it.id },
				description = "from the conformation space named: \"${confSpace.name}\""
			))
		}
	}
}
