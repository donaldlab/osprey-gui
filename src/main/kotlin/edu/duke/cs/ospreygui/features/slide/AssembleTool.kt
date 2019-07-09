package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.ByteFlags
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import cuchaz.kludge.vulkan.ColorRGBA
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.*
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.molecule.*
import edu.duke.cs.molscope.render.RenderEffect
import edu.duke.cs.molscope.tools.toYesNo
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.forcefield.amber.CrdIO
import edu.duke.cs.ospreygui.forcefield.amber.Leap
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.forcefield.amber.TopIO
import edu.duke.cs.ospreygui.io.tempFolder
import edu.duke.cs.ospreygui.io.toPDB
import edu.duke.cs.ospreygui.prep.MoleculePrep


class AssembleTool(val prep: MoleculePrep) : SlideFeature {

	override val id = FeatureId("prep.assemble")

	private val winState = WindowState()

	private var highlightedAtom: Atom? = null
	private var highlightedMol: Molecule? = null

	private inner class Selection(val mol: Molecule) {

		val numHeavyAtoms = mol.atoms.count { it.element != Element.Hydrogen }
		val numHydrogens = mol.atoms.size - numHeavyAtoms
		val numBonds = mol.bonds.count()

		val isAssembled get() = prep.isAssembled(mol)
	}
	private var selection: Selection? = null

	private val inclusionChecks = prep.partition
		.map { (_, mol) -> mol }
		.associateWith { Ref.of(true) }

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Assemble")) {
			winState.isOpen = true
		}
	}

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		winState.render(
			whenOpen = {

				var highlightedMol: Molecule? = null

				begin("Assemble##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize))

				// show the molecule partition
				for ((type, mol) in prep.partition) {

					// show the molecule type
					if (selectable("$mol##${System.identityHashCode(mol)}", selection?.mol == mol)) {
						// select the mol
						selection = Selection(mol)
					}
					if (isItemHovered()) {
						highlightedMol = mol
					}

					indent(10f)

					when (type) {
						MoleculeType.Protein -> {
							mol as Polymer
							for (chain in mol.chains) {
								text("chain ${chain.id}: ${chain.residues.size} amino acids")
							}
						}
						MoleculeType.SmallMolecule -> {
							text("${mol.atoms.size} atoms")
						}
						MoleculeType.Solvent -> {
							mol as Polymer
							text("${mol.chains.first().residues.size} molecules")
						}
						else -> Unit // TODO: display stats for other molecule types?
					}

					// show assembly state
					renderAssembledFlag(mol)

					// checkbox to include the molecule in the prep or not
					val pInclude = inclusionChecks[mol]!!
					if (checkbox("Include##${System.identityHashCode(mol)}", pInclude)) {
						prep.setIncluded(mol, pInclude.value, slide)
					}

					unindent(10f)
					spacing()
					spacing()
				}

				separator()
				spacing()
				spacing()

				// show clear/assemble buttons, when a molecule is selected
				val selection = selection
				if (selection == null) {
					text("Make a selection above to enable assembly tools")
					pushStyleDisabled()
				} else {
					text("Selected: ${selection.mol}")
				}
				indent(10f)
				if (selection != null) {
					renderAssembledFlag(selection.mol)
					text("Heavy Atoms: ${selection.numHeavyAtoms}")
					text("Hydrogens: ${selection.numHydrogens}")
					text("Bonds: ${selection.numBonds}")
				}

				if (button("Assemble")) {
					selection?.mol?.let { assemble(it, slide) }
				}
				sameLine()
				infoTip("""
					|This calls LEaP from AmberTools to add any missing
					|atoms or bonds to the molecule.
				""".trimMargin())

				if (button("Reset")) {
					selection?.mol?.let { reset(it, slide) }
				}
				sameLine()
				infoTip("""
					|This restores the molecule to its original imported state.
					|This can remove bonds and atoms from the molecule.
				""".trimMargin())

				unindent(10f)
				if (selection == null) {
					popStyleDisabled()
				}

				end()

				updateHighlight(slide, slidewin.mouseTarget, highlightedMol)
			},
			onClose = {
				// clear highlights
				slide.views
					.filterIsInstance<MoleculeRenderView>()
					.forEach { it.renderEffects.clear() }
			}
		)
	}

	private fun Commands.renderAssembledFlag(mol: Molecule) {
		val isAssembled = prep.isAssembled(mol)
		val isIncluded = prep.isIncluded(mol)
		val text = "Assembled: ${isAssembled.toYesNo}"
		if (isIncluded) {
			textColored(
				if (isAssembled) {
					ColorRGBA.Int(0, 255, 0)
				} else {
					ColorRGBA.Int(255, 0, 0)
				},
				text
			)
		} else {
			text(text)
		}
	}

	private fun updateHighlight(slide: Slide.Locked, mouseTarget: ViewIndexed?, guiMol: Molecule?) {

		// use the mol from the GUI, or try to get one from the mouse target
		val mol = guiMol ?:
			(mouseTarget?.target as? Atom)?.let { atom ->

				// if it's the same atom as last time, return the same mol
				if (highlightedAtom == atom) {
					return@let highlightedMol
				}
				highlightedAtom = atom

				// find the molecule containing this atom
				return@let prep.partition
					.find { (_, mol) -> mol.atoms.contains(atom) }
					?.second
			}

		// skip if no change
		if (highlightedMol == mol) {
			return
		}
		highlightedMol = mol

		// clear any previous highlights
		val views = slide.views.filterIsInstance<MoleculeRenderView>()
		views.forEach { it.renderEffects.clear() }

		// update the highlight for the mol, if any
		views
			.find { it.mol == mol }
			?.let { view -> view.renderEffects[MoleculeSelectors.all] = hoverEffect }
	}

	private fun assemble(mol: Molecule, slide: Slide.Locked) {

		// TODO: call this in a separate thread?

		// call LEaP to get the missing atoms and bonds
		val results = tempFolder("leap") { cwd ->
			Leap.run(
				cwd,
				filesToWrite = mapOf(
					"mol.pdb" to mol.toPDB()
				),
				// TODO: allow choosing forcefield?
				commands = """
					|source leaprc.ff96
					|mol = loadPdb mol.pdb
					|saveamberparm mol mol.top mol.crd
				""".trimMargin(),
				filesToRead = listOf("mol.top", "mol.crd")
			)
		}

		// TEMP: what happened?
		println("LEaP exit code: ${results.exitCode}")
		results.stdout.forEach { println("LEaP out: $it") }
		results.stderr.forEach { println("LEaP err: $it") }

		// parse the results from LEaP
		val top = TopIO.read(results.files["mol.top"] ?: throw NoSuchElementException("missing toplogy file"))
		val crd = CrdIO.read(results.files["mol.crd"] ?: throw NoSuchElementException("missing coords file"))

		// create a new mol with the new atoms and bonds
		val assembledMol = mol.copy()
		top.mapTo(assembledMol).apply {
			val numAtomsAdded = addMissingAtoms(crd)
			val numBondsAdded = setBonds()
			println("added $numAtomsAdded atoms, $numBondsAdded bonds")
		}

		// save the assembled mol
		prep.setAssembled(mol, assembledMol, slide)
		selection = Selection(mol)
	}

	fun reset(mol: Molecule, slide: Slide.Locked) {
		prep.setAssembled(mol, null, slide)
		selection = Selection(mol)
	}
}

private val hoverEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Outset),
	200u, 200u, 200u
)
