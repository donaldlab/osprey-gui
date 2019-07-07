package edu.duke.cs.ospreygui.prep

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.vulkan.Extent2D
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.slide.MenuRenderSettings
import edu.duke.cs.molscope.gui.features.slide.NavigationTool
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.view.BallAndStick
import edu.duke.cs.ospreygui.features.slide.BondEditor
import edu.duke.cs.ospreygui.features.slide.SaveOMOL


class MoleculePrep(
	private val win: WindowCommands,
	var mol: Molecule
) {

	val slide = Slide(mol.name, initialSize = Extent2D(640, 480)).apply {
		lock { s ->

			s.views.add(BallAndStick(mol))
			s.camera.lookAtEverything()

			s.features.menu("File") {
				add(SaveOMOL())
				addSeparator()
				add(ClosePrep())
			}
			s.features.menu("View") {
				add(NavigationTool())
				add(MenuRenderSettings())
			}
			// TODO: teLeap features?
			s.features.menu("Prepare") {
				add(BondEditor())
			}
		}
		win.addSlide(this)
	}

	inner class ClosePrep : SlideFeature {

		override val id = FeatureId("prep.close")

		override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
			if (menuItem("Close")) {
				win.removeSlide(this@MoleculePrep.slide)
			}
		}
	}
}
