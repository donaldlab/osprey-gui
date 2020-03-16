package edu.duke.cs.ospreygui.features.win

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.WindowFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.io.UserSettings
import edu.duke.cs.ospreygui.io.fromOMOL
import edu.duke.cs.ospreygui.io.read
import edu.duke.cs.ospreygui.prep.MoleculePrep
import java.nio.file.Path


class OpenOMOL : WindowFeature {

	override val id = FeatureId("open.omol")

	val filterList = FilterList(listOf("omol"))

	override fun menu(imgui: Commands, win: WindowCommands) = imgui.run {
		if (menuItem("Open OMOL")) {
			FileDialog.openFile(filterList, UserSettings.openSaveDir)?.let { path ->
				UserSettings.openSaveDir = path.parent
				open(win, path)
			}
		}
	}

	private fun open(win: WindowCommands, path: Path) = win.showExceptions {

		// resume a previous prep
		val mols = Molecule.fromOMOL(
			path.read(),
			// be generous in the GUI and don't crash, sometimes users edit these files by hand
			throwOnMissingAtoms = false
		)
		MoleculePrep(win, mols)
	}
}
