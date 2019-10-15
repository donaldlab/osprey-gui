package edu.duke.cs.ospreygui.prep

import cuchaz.kludge.vulkan.Extent2D
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.features.slide.CloseSlide
import edu.duke.cs.molscope.gui.features.slide.MenuRenderSettings
import edu.duke.cs.molscope.gui.features.slide.NavigationTool
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.view.BallAndStick
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.defaultRenderSettings
import edu.duke.cs.ospreygui.features.slide.*
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.forcefield.amber.partition
import edu.duke.cs.ospreygui.io.ConfLib
import java.util.*
import kotlin.NoSuchElementException


class MoleculePrep(
	win: WindowCommands,
	mols: List<Molecule>
) {

	// partition the molecule into pieces AMBER can understand
	// except, combine all the solvent molecules into one "molecule"
	val partition = mols.partition(combineSolvent = true)

	var name = mols.firstOrNull()?.name ?: "Prep"

	// include all molecules by default
	private val isIncluded = IdentityHashMap<Molecule,Boolean>()
		.apply {
			partition
				.map { (_, mol) -> mol }
				.forEach { mol -> this[mol] = true }
		}

	fun isIncluded(mol: Molecule) =
		isIncluded[mol] ?: throw NoSuchElementException("mol was not found in this prep")

	fun setIncluded(mol: Molecule, isIncluded: Boolean, slide: Slide.Locked) {

		this.isIncluded[mol] = isIncluded

		// update the views
		val existingView = slide.views.find { it is MoleculeRenderView && it.mol == mol }
		if (isIncluded) {
			if (existingView == null) {
				slide.views.add(BallAndStick(mol))
			}
		} else {
			if (existingView != null) {
				slide.views.remove(existingView)
			}
		}
	}

	fun getIncludedTypedMols(): List<Pair<MoleculeType,Molecule>> =
		partition
			.filter { (_, mol) -> isIncluded[mol] == true }

	fun getIncludedMols(): List<Molecule> =
		getIncludedTypedMols()
			.map { (_, mol) -> mol }

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

	val mutablePositionsByMol: MutableMap<Molecule,MutableList<DesignPosition>> = HashMap()
	// TODO: flexible positions

	// make the slide last, since many slide features need to access the prep
	val slide = Slide(name, initialSize = Extent2D(640, 480)).apply {
		lock { s ->

			// make a render view for each molecule in the partition
			for (mol in getIncludedMols()) {
				s.views.add(BallAndStick(mol))
			}
			s.camera.lookAtEverything()

			s.features.menu("File") {
				add(SaveOMOL(this@MoleculePrep))
				addSeparator()
				add(ExportPDB(this@MoleculePrep))
				add(ExportMol2(this@MoleculePrep))
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
			s.features.menu("Prepare") {
				add(FilterTool(this@MoleculePrep))
				add(MissingAtomsEditor())
				add(BondEditor())
				add(ProtonationEditor())
				add(MinimizerTool())
			}
			s.features.menu("Design") {
				add(MutationEditor(this@MoleculePrep))
			}
		}
		win.addSlide(this)
	}
}
