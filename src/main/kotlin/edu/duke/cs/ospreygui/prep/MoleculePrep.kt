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
import edu.duke.cs.ospreygui.features.slide.AssembleTool
import edu.duke.cs.ospreygui.features.slide.SaveOMOL
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.forcefield.amber.combine
import edu.duke.cs.ospreygui.forcefield.amber.partition
import java.util.*
import kotlin.NoSuchElementException


class MoleculePrep(
	win: WindowCommands,
	var mol: Molecule
) {

	// partition the molecule into pieces AMBER can understand
	// except, combine all the solvent molecules into one "molecule"
	val partition = run {
		val partition = mol.partition()
		val combinedSolvent = partition
			.filter { (type, _) -> type == MoleculeType.Solvent }
			.map { (_, mol) -> mol }
			.combine(MoleculeType.Solvent.name)
		return@run partition
			.filter { (type, _) -> type != MoleculeType.Solvent }
			.toMutableList() + listOf(MoleculeType.Solvent to combinedSolvent)
	}

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

	fun getIncludedMols(): List<Molecule> =
		partition
			.filter { (_, mol) -> isIncluded[mol] == true }
			.map { (_, mol) -> mol }

	fun isAssembled(mol: Molecule) =
		assembledMols[mol] != null

	fun setAssembled(baseMol: Molecule, assembledMol: Molecule?, slide: Slide.Locked) {
		removeRenderView(slide, baseMol)
		if (assembledMol != null) {
			assembledMols[baseMol] = assembledMol
		} else {
			assembledMols.remove(baseMol)
		}
		addRenderView(slide, baseMol)
	}

	fun getAssembled(baseMol: Molecule) = assembledMols[baseMol]

	private fun removeRenderView(slide: Slide.Locked, baseMol: Molecule) {
		// remove whichever is present, the base mol, or the assembled mol
		val assembledMol = assembledMols[baseMol]
		slide.views.removeIf { it is MoleculeRenderView && (it.mol == baseMol || it.mol == assembledMol) }
	}

	private fun addRenderView(slide: Slide.Locked, baseMol: Molecule) {
		// use the assembled mol if available, otherwise the base mol
		slide.views.add(BallAndStick(assembledMols[baseMol] ?: baseMol))
	}

	// make the slide last, since the AssembleTool needs properties from the prep
	val slide = Slide(mol.name, initialSize = Extent2D(640, 480)).apply {
		lock { s ->

			// make a render view for each molecule in the partition
			for (mol in getIncludedMols()) {
				s.views.add(BallAndStick(mol))
			}
			s.camera.lookAtEverything()

			s.features.menu("File") {
				add(SaveOMOL())
				addSeparator()
				add(CloseSlide(win))
			}
			s.features.menu("View") {
				add(NavigationTool())
				add(MenuRenderSettings())
			}
			s.features.menu("Prepare") {
				add(AssembleTool(this@MoleculePrep))
			}
		}
		win.addSlide(this)
	}
}
