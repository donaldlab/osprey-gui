package edu.duke.cs.ospreygui.features.win

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.WindowFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.slide.MenuRenderSettings
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.view.BallAndStick
import edu.duke.cs.ospreygui.features.slide.BondEditor
import edu.duke.cs.ospreygui.features.slide.SaveOMOL
import edu.duke.cs.ospreygui.io.fromOMOL
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

		// load the mol
		val mol = Molecule.fromOMOL(path.toFile().readText(Charsets.UTF_8))

		// TODO: share this with ImportPDB
		// prepare a slide for the molecule
		win.addSlide(Slide(mol.name).apply {
			lock { s ->

				s.views.add(BallAndStick(mol))
				s.camera.lookAtEverything()

				s.features.menu("File") {
					add(SaveOMOL())
				}
				s.features.menu("View") {
					add(MenuRenderSettings())
				}
				s.features.menu("Edit") {
					add(BondEditor())
				}
			}
		})
	}
}
