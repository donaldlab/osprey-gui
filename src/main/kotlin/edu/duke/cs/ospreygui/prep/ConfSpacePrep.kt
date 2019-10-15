package edu.duke.cs.ospreygui.prep

import cuchaz.kludge.vulkan.Extent2D
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.features.slide.CloseSlide
import edu.duke.cs.molscope.gui.features.slide.MenuRenderSettings
import edu.duke.cs.molscope.gui.features.slide.NavigationTool
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.tools.identityHashSet
import edu.duke.cs.molscope.view.BallAndStick
import edu.duke.cs.ospreygui.defaultRenderSettings
import edu.duke.cs.ospreygui.features.slide.*
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.io.ConfLib
import java.util.*


class ConfSpacePrep(
	win: WindowCommands,
	val mols: List<Pair<MoleculeType,Molecule>>
) {

	// TODO: edit the name in the UI somewhere?
	var name = "Conformation Space"

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

	val designPositionsByMol: MutableMap<Molecule,MutableList<DesignPosition>> = HashMap()

	class PositionConfSpace {
		var wildTypeFragment: ConfLib.Fragment? = null
		val mutations: MutableSet<ConfLib.Fragment> = identityHashSet()
		val flexibility: MutableMap<ConfLib.Fragment,Set<ConfLib.Conf>> = IdentityHashMap()
	}
	class PositionConfSpaces {

		private val confSpaces: MutableMap<DesignPosition,PositionConfSpace> = HashMap()

		operator fun get(pos: DesignPosition) = confSpaces[pos]
		fun getOrMake(pos: DesignPosition) = confSpaces.getOrPut(pos) { PositionConfSpace() }
		fun remove(pos: DesignPosition) = confSpaces.remove(pos)
	}
	val positionConfSpaces = PositionConfSpaces()


	// make the slide last, since many slide features need to access the prep
	val slide = Slide(name, initialSize = Extent2D(640, 480)).apply {
		lock { s ->

			// make a render view for each molecule
			for ((_, mol) in mols) {
				s.views.add(BallAndStick(mol))
			}
			s.camera.lookAtEverything()

			s.features.menu("File") {
				// TODO: safe conf space
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
			s.features.menu("Design") {
				add(MutationEditor(this@ConfSpacePrep))
			}
		}
		win.addSlide(this)
	}
}
