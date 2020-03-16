package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.gui.enabledIf
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.io.UserSettings
import edu.duke.cs.ospreygui.io.toToml
import edu.duke.cs.ospreygui.io.write
import edu.duke.cs.ospreygui.prep.ConfSpace
import java.nio.file.Path


/**
 * Useful for breaking a conformation space of a complex
 * into unbound conformation spaces for each molecule in the complex.
 */
class SplitConfSpace(val confSpace: ConfSpace) : SlideFeature {

	override val id = FeatureId("save.splitConfspace")

	companion object {
		const val extension = "confspace"
	}

	val winState = WindowState()
	val nameBuf = Commands.TextBuffer(1024)

	val filterList = FilterList(listOf(extension))

	class MolInfo(val mol: Molecule, val type: MoleculeType, index: Int) {
		val label = "$type: $mol###mol$index"
		val pSelected = Ref.of(false)
	}
	val molInfos = ArrayList<MolInfo>()

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Split Conformation Space")) {
			winState.isOpen = true
		}
	}

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		winState.render(
			onOpen = {

				// make the mol infos
				molInfos.clear()
				for ((type, mol) in confSpace.mols) {
					molInfos.add(MolInfo(mol, type, molInfos.size))
				}

				// reset the text buffer
				nameBuf.text = confSpace.name
			},
			whenOpen = {
				window("Split Conformation Space##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

					// show a checkbox for each molecule
					for ((i, info) in molInfos.withIndex()) {

						// breathe a little
						if (i > 0) {
							spacing()
							spacing()
							spacing()
						}

						checkbox(info.label, info.pSelected)
					}

					// breathe a little
					spacing()
					spacing()
					spacing()

					// choose a name
					inputText("Name", nameBuf)

					// breathe a little
					spacing()
					spacing()
					spacing()

					val selectedMols = molInfos
						.filter { it.pSelected.value == true }
						.map { it.mol }

					// show a save button
					val selmsg = if (selectedMols.size == 1) {
						"1 molecule"
					} else {
						"${selectedMols.size} molecules"
					}
					enabledIf(selectedMols.isNotEmpty()) {
						if (button("Save $selmsg###save")) {
							FileDialog.saveFile(filterList, UserSettings.openSaveDir)?.let { path ->
								UserSettings.openSaveDir = path.parent
								save(slidewin, path, selectedMols, nameBuf.text)
							}
						}
					}
				}
			}
		)
	}

	private fun save(slidewin: SlideCommands, path: Path, mols: List<Molecule>, newName: String) = slidewin.showExceptions {

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

			// split the conf space using the selected molecules and save it
			confSpace
				.copy(mols)
				.apply {
					name = newName
				}
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
