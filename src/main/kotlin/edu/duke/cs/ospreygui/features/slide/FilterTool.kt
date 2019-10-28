package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.ByteFlags
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import cuchaz.kludge.tools.toFloat
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.*
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.molecule.*
import edu.duke.cs.molscope.render.RenderEffect
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.prep.MoleculePrep
import org.joml.Vector3d


class FilterTool(val prep: MoleculePrep) : SlideFeature {

	override val id = FeatureId("prep.filter")

	private val winState = WindowState()

	private var highlightedAtom: Atom? = null
	private var highlightedMol: Molecule? = null

	private val inclusionChecks = prep.partition
		.map { (_, mol) -> mol }
		.associateWith { Ref.of(true) }

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Filter")) {
			winState.isOpen = true
		}
	}

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		// render the main window
		winState.render(
			whenOpen = {

				var hoveredMol: Molecule? = null

				window("Filter##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

					// show the molecule partition
					prep.partition.forEachIndexed { i, (type, mol) ->

						if (i > 0) {
							// let the entries breathe
							spacing()
							spacing()
							separator()
							spacing()
							spacing()
						}

						// show the molecule type
						selectable("$mol##${System.identityHashCode(mol)}", highlightedMol == mol)
						if (isItemHovered()) {
							hoveredMol = mol
						}

						// show a context menu to center the camera on the molecule
						popupContextItem("centerCamera${System.identityHashCode(mol)}") {

							if (button("Center Camera")) {

								// center on the molecule centroid
								val center = Vector3d().apply {
									mol.atoms.forEach { add(it.pos) }
									div(mol.atoms.size.toDouble())
								}
								slidewin.camera.lookAt(center.toFloat())

								closeCurrentPopup()
							}
						}

						indent(10f)

						when (type) {
							MoleculeType.Protein -> {
								mol as Polymer
								for (chain in mol.chains) {
									text("chain ${chain.id}: ${chain.residues.size} amino acids")
								}
							}
							MoleculeType.DNA,
							MoleculeType.RNA -> {
								mol as Polymer
								for (chain in mol.chains) {
									text("chain ${chain.id}: ${chain.residues.size} bases")
								}
							}
							MoleculeType.SmallMolecule,
							MoleculeType.AtomicIon -> {
								text("${mol.atoms.size} atoms")
							}
							MoleculeType.Solvent -> {
								mol as Polymer
								text("${mol.chains.first().residues.size} molecules")
							}
							else -> Unit
						}

						// checkbox to include the molecule in the prep or not
						val pInclude = inclusionChecks.getValue(mol)
						if (checkbox("Include##${System.identityHashCode(mol)}", pInclude)) {
							prep.setIncluded(mol, pInclude.value, slide)
						}

						unindent(10f)
					}
				}

				updateHighlight(slide, slidewin.mouseTarget, hoveredMol)
			},
			onClose = {
				// clear highlights
				slide.views
					.filterIsInstance<MoleculeRenderView>()
					.forEach { it.renderEffects.clear() }
			}
		)
	}

	private fun updateHighlight(slide: Slide.Locked, mouseTarget: ViewIndexed?, guiMol: Molecule?) {

		val (mol, atom) = if (guiMol != null) {

			// use the mol selected from the GUI
			guiMol to null

		} else {

			// get the atom/mol selected by the mouse target
			val atom = mouseTarget?.target as? Atom
			if (atom != null) {

				// if it's the same atom as last time, return the same mol
				if (highlightedAtom == atom) {
					highlightedMol to atom
				}

				// find the molecule containing this atom
				val mol = prep.partition
					.find { (_, mol) -> mol.atoms.contains(atom) }
					?.second

				mol to atom

			} else {
				null to null
			}
		}

		// skip if no change
		if (highlightedMol == mol && highlightedAtom == atom) {
			return
		}
		highlightedMol = mol
		highlightedAtom = atom

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
