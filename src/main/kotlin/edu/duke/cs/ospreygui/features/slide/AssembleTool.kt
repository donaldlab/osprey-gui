package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.ByteFlags
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.ViewIndexed
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.MoleculeSelectors
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.render.RenderEffect
import edu.duke.cs.molscope.view.BallAndStick
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.prep.MoleculePrep


class AssembleTool(val prep: MoleculePrep) : SlideFeature {

	override val id = FeatureId("prep.assemble")

	private val winState = WindowState()

	private var highlightedAtom: Atom? = null
	private var highlightedMol: Molecule? = null

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
					selectable(type.name + (mol.type?.let { ": $it" } ?: ""), false)
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

					// show controls
					val pInclude = inclusionChecks[mol]!!
					if (checkbox("Include##${System.identityHashCode(mol)}", pInclude)) {
						updateInclusion(slide, mol, pInclude.value)
					}

					unindent(10f)
					spacing()
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

	private fun updateInclusion(slide: Slide.Locked, mol: Molecule, isIncluded: Boolean) {

		prep.included[mol] = isIncluded

		// update the slide views
		if (isIncluded) {
			slide.views.add(BallAndStick(mol))
		} else {
			slide.views.removeIf { it is MoleculeRenderView && it.mol == mol }
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
}

private val hoverEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Outset),
	200u, 200u, 200u
)
