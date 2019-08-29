package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.molecule.combine
import edu.duke.cs.ospreygui.io.toOMOL
import edu.duke.cs.ospreygui.io.write
import edu.duke.cs.ospreygui.prep.MoleculePrep
import java.nio.file.Path
import java.nio.file.Paths


class SaveOMOL(val prep: MoleculePrep) : SlideFeature {

	override val id = FeatureId("save.omol")

	companion object {
		const val extension = "omol.toml"
	}

	val filterList = FilterList(listOf(extension))
	var dir = Paths.get(".")

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Save OMOL")) {
			FileDialog.saveFile(filterList, dir)?.let { path ->
				dir = path.parent
				save(slide, slidewin, path)
			}
		}
	}

	private fun save(slide: Slide.Locked, slidewin: SlideCommands, path: Path) = slidewin.showExceptions {

		// append the file extension if needed
		var filename = path.fileName.toString()
		if (!filename.endsWith(".$extension")) {
			filename += ".$extension"
		}
		val pathWithExt = path.parent.resolve(filename)

		// combine the included assembled mols and save the file
		prep.getIncludedMols()
			.combine(prep.mol.name).first
			.toOMOL()
			.write(pathWithExt)

		// TODO: feedback to the user that the save worked?
	}
}
