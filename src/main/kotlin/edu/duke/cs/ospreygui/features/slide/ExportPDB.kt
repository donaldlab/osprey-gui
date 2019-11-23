package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.combine
import edu.duke.cs.ospreygui.io.ChainGeneratorSingleResidue
import edu.duke.cs.ospreygui.io.ChainIdGeneratorAZ
import edu.duke.cs.ospreygui.io.toPDB
import edu.duke.cs.ospreygui.io.write
import java.nio.file.Path
import java.nio.file.Paths


class ExportPDB(val getter: () -> List<Molecule>) : SlideFeature {

	override val id = FeatureId("export.pdb")

	companion object {
		const val extension = "pdb"
	}

	val filterList = FilterList(listOf(extension))
	var dir = Paths.get("").toAbsolutePath()

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Export PDB")) {
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

		// combine the mols and save the file
		val chainIdGenerator = ChainIdGeneratorAZ()
		val chainGenerator = ChainGeneratorSingleResidue(chainIdGenerator)
		getter()
			.combine("name ignored by PDB writer", chainIdGenerator, chainGenerator).first
			.toPDB()
			.write(pathWithExt)

		// TODO: feedback to the user that the save worked?
	}
}
