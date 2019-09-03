package edu.duke.cs.ospreygui.prep

import cuchaz.kludge.vulkan.Extent2D
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.features.slide.CloseSlide
import edu.duke.cs.molscope.gui.features.slide.MenuRenderSettings
import edu.duke.cs.molscope.gui.features.slide.NavigationTool
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.render.RenderSettings
import edu.duke.cs.molscope.view.BallAndStick
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.features.slide.*
import edu.duke.cs.ospreygui.forcefield.amber.partition
import java.util.*
import kotlin.NoSuchElementException


class MoleculePrep(
	win: WindowCommands,
	var mol: Molecule
) {

	// partition the molecule into pieces AMBER can understand
	// except, combine all the solvent molecules into one "molecule"
	val partition = mol.partition(combineSolvent = true)

	// include all molecules by default
	private val isIncluded = IdentityHashMap<Molecule,Boolean>()
		.apply {
			partition
				.map { (_, mol) -> mol }
				.forEach { mol -> this[mol] = true }
		}

	private val assembledMols = IdentityHashMap<Molecule,Molecule>()

	fun isIncluded(mol: Molecule) =
		isIncluded[mol] ?: throw NoSuchElementException("mol was not found in this prep")

	fun setIncluded(mol: Molecule, isIncluded: Boolean, slide: Slide.Locked) {
		this.isIncluded[mol] = isIncluded
		if (isIncluded) {
			addRenderView(slide, mol)
		} else {
			removeRenderView(slide, mol)
		}
	}

	/** the base molecules */
	fun getIncludedMols(): List<Molecule> =
		partition
			.filter { (_, mol) -> isIncluded[mol] == true }
			.map { (_, mol) -> mol }

	private fun removeRenderView(slide: Slide.Locked, baseMol: Molecule) {
		// remove whichever is present, the base mol, or the assembled mol
		val assembledMol = assembledMols[baseMol]
		slide.views.removeIf { it is MoleculeRenderView && (it.mol == baseMol || it.mol == assembledMol) }
	}

	private fun addRenderView(slide: Slide.Locked, baseMol: Molecule) {
		// use the assembled mol if available, otherwise the base mol
		slide.views.add(BallAndStick(assembledMols[baseMol] ?: baseMol))
	}

	// make the slide last, since the FilterTool needs properties from the prep
	val slide = Slide(mol.name, initialSize = Extent2D(640, 480)).apply {
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
				add(MenuRenderSettings(RenderSettings().apply {
					// these settings looked nice on a small protein
					shadingWeight = 1.4f
					lightWeight = 1.4f
					depthWeight = 1.0f
				}))
				add(ClashViewer())
			}
			s.features.menu("Prepare") {
				add(FilterTool(this@MoleculePrep))
				add(MissingAtomsEditor())
				add(BondEditor())
				add(ProtonationEditor())
				add(MinimizerTool())
			}
		}
		win.addSlide(this)
	}
}
