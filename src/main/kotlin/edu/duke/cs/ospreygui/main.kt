package edu.duke.cs.ospreygui

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.Ref
import cuchaz.kludge.tools.autoCloser
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.Window
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.WindowFeature
import edu.duke.cs.molscope.molecule.*
import edu.duke.cs.molscope.view.BallAndStick
import edu.duke.cs.osprey.restypes.HardCodedResidueInfo
import edu.duke.cs.osprey.structure.PDBIO
import edu.duke.cs.ospreygui.features.slide.BondEditor
import edu.duke.cs.ospreygui.prep.guessBonds
import edu.duke.cs.osprey.structure.Molecule as OspreyMolecule
import edu.duke.cs.osprey.structure.Atom as OspreyAtom
import java.io.File
import java.util.*


fun main() = autoCloser {

	// use osprey to load a PDB file
	val ospreyMol = PDBIO.readFile(File("../osprey3/test-resources/1CC8.ss.pdb"))
	println("loaded ${ospreyMol.residues.size} residues")

	// convert the molecule to molscope format
	val mol = ospreyMol.toPolymer("1CC8")

	/* TEMP
	// guess the bonds
	for (bond in mol.guessBonds()) {
		mol.bonds.add(bond.a1, bond.a2)
	}
	*/

	// open a window
	val win = Window(
		width = 800,
		height = 600,
		title = "MolScope"
	).autoClose()

	// create a new window feature
	win.features.add(object : WindowFeature("App", "Feature") {

		val pOpen = Ref.of(false)

		override fun menu(imgui: Commands, win: WindowCommands) = imgui.run {
			if (menuItem("Feature")) {
				pOpen.value = true
			}
		}

		override fun gui(imgui: Commands, win: WindowCommands) = imgui.run {
			if (pOpen.value) {
				begin("Feature", pOpen)
				text("my new feature!")
				end()
			}
		}
	})

	// prepare a slide for the molecule
	win.slides.add(Slide(mol.name).apply {
		lock { s ->

			s.views.add(BallAndStick(mol))
			s.camera.lookAtEverything()

			s.features.add(BondEditor())
		}
	})

	win.waitForClose()

} // end of scope here cleans up all autoClose() resources


fun OspreyMolecule.toPolymer(name: String) =
	Polymer(name).apply {

		// convert the atoms
		val atomMap = IdentityHashMap<OspreyAtom,Atom>()
		for (ores in residues) {
			for (oatom in ores.atoms) {
				val atom = Atom(
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
			val atoms = res.atoms.map { atomMap[it]!! }

			// make a best guess for mainchain vs sidechain atoms
			val mainchain = atoms.filter { it.name.toUpperCase() in HardCodedResidueInfo.possibleBBAtomsLookup }
			val sidechain = atoms.filter { it !in mainchain }
			chain.add(Residue(
				res.pdbResNumber,
				mainchain = mainchain,
				sidechains = listOf(sidechain)
			))
		}
	}
