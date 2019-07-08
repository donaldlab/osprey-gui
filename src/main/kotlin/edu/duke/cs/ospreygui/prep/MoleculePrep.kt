package edu.duke.cs.ospreygui.prep

import cuchaz.kludge.vulkan.Extent2D
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.features.slide.CloseSlide
import edu.duke.cs.molscope.gui.features.slide.MenuRenderSettings
import edu.duke.cs.molscope.gui.features.slide.NavigationTool
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.view.BallAndStick
import edu.duke.cs.ospreygui.features.slide.AssembleTool
import edu.duke.cs.ospreygui.features.slide.SaveOMOL
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.forcefield.amber.combine
import edu.duke.cs.ospreygui.forcefield.amber.partition


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

	inner class Included {

		private val mols = partition
			.map { (_, mol) -> mol }
			.associateWith { true }
			.toMutableMap()

		val toList: List<Molecule> get() = mols
			.filter { (_, isIncluded) -> isIncluded }
			.map { (mol, _) -> mol }

		operator fun get(mol: Molecule) = mols[mol] ?: throw NoSuchElementException("mol was not found in this prep")
		operator fun set(mol: Molecule, isIncluded: Boolean) {
			mols[mol] = isIncluded
		}
	}
	val included = Included()

	// make the slide last, since the AssembleTool needs properties from the prep
	val slide = Slide(mol.name, initialSize = Extent2D(640, 480)).apply {
		lock { s ->

			for (mol in included.toList) {
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
