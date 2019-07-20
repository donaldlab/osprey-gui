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
import edu.duke.cs.ospreygui.io.toPDB
import edu.duke.cs.ospreygui.prep.MoleculePrep


class AssembleTool(val prep: MoleculePrep) : SlideFeature {

	override val id = FeatureId("prep.assemble")

	private val winState = WindowState()

	private var highlightedAtom: Atom? = null
	private var highlightedMol: Molecule? = null

	private inner class Selection(val mol: Molecule) {

		private val activeMol = prep.getAssembled(mol) ?: mol

		val numHeavyAtoms = activeMol.atoms.count { it.element != Element.Hydrogen }
		val numHydrogens = activeMol.atoms.size - numHeavyAtoms
		val numBonds = activeMol.bonds.count()

		val isAssembled get() = prep.isAssembled(mol)
	}
	private var selection: Selection? = null

	private val inclusionChecks = prep.partition
		.map { (_, mol) -> mol }
		.associateWith { Ref.of(true) }

	private var leapThread: Thread? = null
	private val leapErrors = ArrayList<Leap.Results>()
	private val leapWinState = WindowState()
	private var leapErrorsTextBuf: Commands.TextBuffer? = null

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Assemble")) {
			winState.isOpen = true
		}
	}

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		// render the main window
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
				} else {
					text("Selected: ${selection.mol}")
				}

				// disable the controls if we have no selection, or leap is already running
				val isEnabled = selection != null && leapThread == null
				if (!isEnabled) {
					pushStyleDisabled()
				}
				indent(10f)
				if (selection != null) {
					renderAssembledFlag(selection.mol)
					text("Heavy Atoms: ${selection.numHeavyAtoms}")
					text("Hydrogens: ${selection.numHydrogens}")
					text("Bonds: ${selection.numBonds}")
				}

				if (button("Assemble")) {
					if (isEnabled) {
						selection?.mol?.let { assemble(it, slide) }
					}
				}
				sameLine()
				infoTip("""
					|This calls LEaP from AmberTools to add any missing
					|atoms or bonds to the molecule.
				""".trimMargin())

				if (button("Reset")) {
					if (isEnabled) {
						selection?.mol?.let { reset(it, slide) }
					}
				}
				sameLine()
				infoTip("""
					|This restores the molecule to its original imported state.
					|This can remove bonds and atoms from the molecule.
				""".trimMargin())

				unindent(10f)
				if (!isEnabled) {
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

		// render a window for LEaP errors, if any
		leapWinState.render(
			whenOpen = {

				begin("LEaP Errors", leapWinState.pOpen, IntFlags.of(Commands.BeginFlags.NoResize))

				val buf = leapErrorsTextBuf
				if (buf != null) {
					inputTextMultiline(
						"",
						buf,
						600f, 400f,
						IntFlags.of(Commands.InputTextFlags.ReadOnly)
					)
					// yes, the horizontal scroll bar doesn't appear automatically for multiline input text widgets
					// this is an ongoing enhancement in ImGUI, but I wouldn't wait for it
				} else {
					text("No errors")
				}

				if (button("Clear")) {
					clearLeapErrors()
					leapWinState.isOpen = false
				}

				end()
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

		// if leap is already running, ignore the assemble call
		if (leapThread != null) {
			return
		}

		// this could take a while, so launch it in a separate thread
		leapThread = Thread {

			// call LEaP to get the missing atoms and bonds
			val results = Leap.run(
				filesToWrite = mapOf(
					"mol.pdb" to mol.toPDB()
				),
				// TODO: allow choosing forcefield?
				commands = """
					|source leaprc.ff96
					|mol = loadPdb mol.pdb
					|saveamberparm mol mol.top mol.crd
				""".trimMargin(),
				filesToRead = listOf("mol.top", "mol.crd", "leap.log")
			)

			// did leap return something useful?
			val topFile = results.files["mol.top"]?.takeIf { it.isNotBlank() }
			val crdFile = results.files["mol.crd"]?.takeIf { it.isNotBlank() }
			if (topFile == null || crdFile == null) {

				// nope, show the error info
				addLeapError(mol, results)

			} else {

				// parse the results from LEaP
				val top = TopIO.read(topFile)
				val crd = CrdIO.read(crdFile)

				// create a new mol with the new atoms and bonds
				val assembledMol = mol.copy()
				top.mapTo(assembledMol).apply {
					addMissingAtoms(crd)
					setBonds()
				}

				// save the assembled mol
				// (but lock the slide again, since we're on a different thread now)
				slide.unlocked.lock { slide ->
					prep.setAssembled(mol, assembledMol, slide)
				}
				selection = Selection(mol)
			}

			// finally, clear the thread variable when we're done
			leapThread = null

		}.apply {
			name = "leap"
			start()
		}
	}

	private fun reset(mol: Molecule, slide: Slide.Locked) {
		prep.setAssembled(mol, null, slide)
		selection = Selection(mol)
	}

	private fun addLeapError(mol: Molecule, results: Leap.Results) {

		leapErrors.add(results)

		// update the text buffer
		leapErrorsTextBuf = Commands.TextBuffer.of(leapErrors.joinToString("\n\n\n") {
			StringBuilder().apply {

				append("LEaP could not assemble the molecule: $mol\n")
				append("Here's the raw log from the leap run:\n")
				append(results.files["leap.log"])

			}.toString()
		})

		// show the leap errors window
		leapWinState.isOpen = true
	}

	private fun clearLeapErrors() {
		leapErrors.clear()
		leapErrorsTextBuf = null
	}
}

private val hoverEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Outset),
	200u, 200u, 200u
)
