package edu.duke.cs.ospreygui.features.win

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.WindowFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.io.fromPDB
import edu.duke.cs.ospreygui.io.read
import edu.duke.cs.ospreygui.prep.MoleculePrep
import java.nio.file.Path
import java.nio.file.Paths


class ImportPDB : WindowFeature {

	override val id = FeatureId("import.pdbl")

	val filterList = FilterList(listOf("pdb"))
	// TEMP: pdb.gz?
	var dir = Paths.get(".")

	override fun menu(imgui: Commands, win: WindowCommands) = imgui.run {
		if (menuItem("Import PDB")) {
			FileDialog.openFile(filterList, dir)?.let { path ->
				dir = path.parent
				open(win, path)
			}
		}
	}

	private fun open(win: WindowCommands, path: Path) = win.showExceptions {

		// start a new prep
		MoleculePrep(win, listOf(Molecule.fromPDB(path.read())))
	}
}
