package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.molecule.combine
import edu.duke.cs.ospreygui.io.ChainGeneratorSingleResidue
import edu.duke.cs.ospreygui.io.ChainIdGeneratorAZ
import edu.duke.cs.ospreygui.io.toMol2
import edu.duke.cs.ospreygui.io.write
import edu.duke.cs.ospreygui.prep.MoleculePrep
import java.nio.file.Path
import java.nio.file.Paths


class ExportMol2(val prep: MoleculePrep, val title: String = "Export Mol2") : SlideFeature {

	override val id = FeatureId("export.mol2")

	companion object {
		const val extension = "mol2"
	}

	val filterList = FilterList(listOf(extension))
	var dir = Paths.get(".")

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem(title)) {
			FileDialog.saveFile(filterList, dir)?.let { path ->
				dir = path.parent
				save(slide, slidewin, path)
			}
		}
	}

	private fun save(slide: Slide.Locked, slidewin: SlideCommands, path: Path) = slidewin.showExceptions {

		// append the file extension if needed
		var filename = path.fileName.toString()
		if (!filename.endsWith(".$extension")) {
			filename += ".$extension"
		}
		val pathWithExt = path.parent.resolve(filename)

		// combine the included assembled mols and save the file
		val chainIdGenerator = ChainIdGeneratorAZ()
		val chainGenerator = ChainGeneratorSingleResidue(chainIdGenerator)
		prep.getIncludedMols()
			.combine(prep.mol.name, chainIdGenerator, chainGenerator).first
			.toMol2()
			.write(pathWithExt)

		// TODO: feedback to the user that the save worked?
	}
}
