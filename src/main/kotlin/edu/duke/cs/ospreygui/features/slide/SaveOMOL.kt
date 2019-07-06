package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.io.toOMOL
import edu.duke.cs.ospreygui.io.write
import java.nio.file.Path
import java.nio.file.Paths


class SaveOMOL : SlideFeature {

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

		// get the molecule (there should be only one view, and hence one molecule)
		val view = slide.views.first() as MoleculeRenderView
		val mol = view.mol

		// save the file
		mol.toOMOL().write(pathWithExt)

		// TODO: feedback to the user that the save worked?
	}
}
