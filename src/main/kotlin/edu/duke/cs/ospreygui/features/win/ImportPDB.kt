package edu.duke.cs.ospreygui.features.win

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.WindowFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.slide.MenuRenderSettings
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.view.BallAndStick
import edu.duke.cs.osprey.restypes.HardCodedResidueInfo
import edu.duke.cs.osprey.structure.Atom
import edu.duke.cs.osprey.structure.Molecule
import edu.duke.cs.osprey.structure.PDBIO
import edu.duke.cs.ospreygui.features.slide.BondEditor
import edu.duke.cs.ospreygui.features.slide.SaveOMOL
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*


class ImportPDB : WindowFeature {

	override val id = FeatureId("import.pdbl")

	val filterList = FilterList(listOf("pdb"))
	// TEMP: pdb.gz?
	var dir = Paths.get(".")

	override fun menu(imgui: Commands, win: WindowCommands) = imgui.run {
		if (menuItem("Import PDB")) {
			FileDialog.openFile(filterList, dir)?.let { path ->
				dir = path.parent
				open(win, path)
			}
		}
	}

	private fun open(win: WindowCommands, path: Path) = win.showExceptions {

		// use osprey to load a PDB file
		val ospreyMol = PDBIO.readFile(path.toFile())

		// convert the molecule to molscope format
		val mol = ospreyMol.toPolymer(path.fileName.toString())

		// TODO: share this with OpenOMOL
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

fun Molecule.toPolymer(name: String) =
	Polymer(name).apply {

		// convert the atoms
		val atomMap = IdentityHashMap<Atom, edu.duke.cs.molscope.molecule.Atom>()
		for (ores in residues) {
			for (oatom in ores.atoms) {
				val atom = edu.duke.cs.molscope.molecule.Atom(
					Element.get(oatom.elementNumber),
					oatom.name,
					oatom.coords[0],
					oatom.coords[1],
					oatom.coords[2]
				)
				atomMap[oatom] = atom
				atoms.add(atom)
			}
		}

		// convert the bonds
		for (ores in residues) {
			for (oatom in ores.atoms) {
				val atom = atomMap[oatom]!!
				for (bondedOatom in oatom.bonds) {
					val bondedAtom = atomMap[bondedOatom]!!
					bonds.add(atom, bondedAtom)
				}
			}
		}

		// convert the residues
		for (res in residues) {

			// get/make the chain
			val chain = chains
				.find { it.id == res.chainId.toString() }
				?: run {
					Polymer.Chain(res.chainId.toString()).apply {
						chains.add(this)
					}
				}

			val atoms = res.atoms.map { atomMap[it]!! }

			// make a best guess for mainchain vs sidechain atoms
			val mainchain = atoms.filter { it.name.toUpperCase() in HardCodedResidueInfo.possibleBBAtomsLookup }
			val sidechain = atoms.filter { it !in mainchain }
			chain.residues.add(Polymer.Residue(
				res.pdbResNumber.substring(1),
				res.type,
				mainchain = mainchain,
				sidechains = listOf(sidechain)
			))
		}
	}

