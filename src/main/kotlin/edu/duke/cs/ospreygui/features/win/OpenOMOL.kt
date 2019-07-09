package edu.duke.cs.ospreygui.features.win

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.WindowFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.io.fromOMOL
import edu.duke.cs.ospreygui.io.read
import edu.duke.cs.ospreygui.prep.MoleculePrep
import java.nio.file.Path
import java.nio.file.Paths


class OpenOMOL : WindowFeature {

	override val id = FeatureId("open.omol")

	val filterList = FilterList(listOf("omol.toml"))
	var dir = Paths.get(".")

	override fun menu(imgui: Commands, win: WindowCommands) = imgui.run {
		if (menuItem("Open OMOL")) {
			FileDialog.openFile(filterList, dir)?.let { path ->
				dir = path.parent
				open(win, path)
			}
		}
	}

	private fun open(win: WindowCommands, path: Path) = win.showExceptions {

		// resume a previous prep
		MoleculePrep(win, Molecule.fromOMOL(path.read()))
	}
}