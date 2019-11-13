package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.ospreygui.io.toToml
import edu.duke.cs.ospreygui.io.write
import edu.duke.cs.ospreygui.prep.ConfSpace
import java.nio.file.Path
import java.nio.file.Paths


class SaveConfSpace(val confSpace: ConfSpace) : SlideFeature {

	override val id = FeatureId("save.confspace")

	companion object {
		const val extension = "confspace.toml"
	}

	val filterList = FilterList(listOf(extension))
	var dir = Paths.get("").toAbsolutePath()

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Save Conformation Space")) {
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

		// save the file
		confSpace
			.toToml()
			.write(pathWithExt)

		// TODO: feedback to the user that the save worked?
	}
}
