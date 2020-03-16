package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.ospreygui.io.UserSettings
import edu.duke.cs.ospreygui.io.toToml
import edu.duke.cs.ospreygui.io.write
import edu.duke.cs.ospreygui.prep.ConfSpace
import java.nio.file.Path


class SaveConfSpace(val confSpace: ConfSpace) : SlideFeature {

	override val id = FeatureId("save.confspace")

	companion object {
		const val extension = "confspace"
	}

	val filterList = FilterList(listOf(extension))

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Save Conformation Space")) {
			FileDialog.saveFile(filterList, UserSettings.openSaveDir)?.let { path ->
				UserSettings.openSaveDir = path.parent
				save(slidewin, path)
			}
		}
	}

	private fun save(slidewin: SlideCommands, path: Path) = slidewin.showExceptions {

		// append the file extension if needed
		var filename = path.fileName.toString()
		if (!filename.endsWith(".$extension")) {
			filename += ".$extension"
		}
		val pathWithExt = path.parent.resolve(filename)

		// backup the current design position conformations
		val positions = confSpace.positions()
		val backups = positions.map { it.makeFragment("backup", "backup") }
		try {

			// put the conf space back into wild-type mode for saving
			for (pos in positions) {
				val posConfSpace = confSpace.positionConfSpaces[pos] ?: continue
				val wtFrag = posConfSpace.wildTypeFragment
				if (wtFrag != null) {
					val wtConf = wtFrag.confs.values.firstOrNull() ?: continue
					pos.setConf(wtFrag, wtConf)
				}
			}

			// save the file
			confSpace
				.toToml()
				.write(pathWithExt)

		} finally {

			// restore the backups
			for ((pos, backup) in positions.zip(backups)) {
				pos.setConf(backup, backup.confs.values.first())
			}
		}

		// TODO: feedback to the user that the save worked?
	}
}
