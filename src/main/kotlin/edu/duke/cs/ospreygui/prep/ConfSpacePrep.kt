package edu.duke.cs.ospreygui.prep

import cuchaz.kludge.vulkan.Extent2D
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.features.slide.CloseSlide
import edu.duke.cs.molscope.gui.features.slide.MenuRenderSettings
import edu.duke.cs.molscope.gui.features.slide.NavigationTool
import edu.duke.cs.molscope.view.BallAndStick
import edu.duke.cs.ospreygui.defaultRenderSettings
import edu.duke.cs.ospreygui.features.slide.*
import edu.duke.cs.ospreygui.io.ConfLib
import java.util.*


class ConfSpacePrep(
	win: WindowCommands,
	val confSpace: ConfSpace
) {

	class ConfLibs : Iterable<ConfLib> {

		private val conflibs = ArrayList<ConfLib>()

		override fun iterator() = conflibs.iterator()

		fun add(toml: String): ConfLib {

			val conflib = ConfLib.from(toml)

			// don't load the same library more than once
			if (conflibs.any { it.name == conflib.name }) {
				throw DuplicateConfLibException(conflib)
			}

			conflibs.add(conflib)

			return conflib
		}
	}
	val conflibs = ConfLibs()

	class DuplicateConfLibException(val conflib: ConfLib) : RuntimeException("Conformation library already loaded: ${conflib.name}")

	// make the slide last, since many slide features need to access the prep
	val slide = Slide(confSpace.name, initialSize = Extent2D(640, 480)).apply {
		lock { s ->

			// make a render view for each molecule
			for ((_, mol) in confSpace.mols) {
				s.views.add(BallAndStick(mol))
			}
			s.camera.lookAtEverything()

			s.features.menu("File") {
				// TODO: save conf space
				addSeparator()
				// TODO: export compiled conf space
				addSeparator()
				addSpacing(4)
				addSeparator()
				add(CloseSlide(win))
			}
			s.features.menu("View") {
				add(NavigationTool())
				add(MenuRenderSettings(defaultRenderSettings))
				add(ClashViewer())
			}
			s.features.menu("Edit") {
				add(MutationEditor(this@ConfSpacePrep))
				add(ConformationEditor(this@ConfSpacePrep))
			}
		}
		win.addSlide(this)
	}
}
