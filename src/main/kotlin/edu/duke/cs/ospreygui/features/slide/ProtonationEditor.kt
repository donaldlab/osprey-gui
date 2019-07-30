package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.ByteFlags
import cuchaz.kludge.tools.IntFlags
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.ClickTracker
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.render.RenderEffect
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.forcefield.amber.Protonation
import edu.duke.cs.ospreygui.forcefield.amber.deprotonate
import edu.duke.cs.ospreygui.forcefield.amber.protonate
import edu.duke.cs.ospreygui.forcefield.amber.protonations


class ProtonationEditor : SlideFeature {

	override val id = FeatureId("edit.protonation")

	private val winState = WindowState()
	private val clickTracker = ClickTracker()
	private var selection: Selection? = null

	private fun Slide.Locked.molViews() = views.mapNotNull { it as? MoleculeRenderView }

	private fun List<MoleculeRenderView>.clearSelections() = forEach { it.renderEffects.clear() }

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Protonation")) {
			winState.isOpen = true
		}
	}

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		val molViews = slide.molViews()

		winState.render(
			onOpen = {
				// add the hover effect
				slidewin.hoverEffects[id] = hoverEffect
			},
			whenOpen = {

				// did we click anything?
				if (clickTracker.clicked(slidewin)) {

					// clear any previous selection
					molViews.clearSelections()
					selection = null

					// select the heavy atom from the click, if any
					slidewin.mouseTarget?.let { target ->
						(target.view as? MoleculeRenderView)?.let { view ->
							(target.target as? Atom)?.let { atom ->
								if (atom.element != Element.Hydrogen) {
									selection = Selection(view, atom)
								}
							}
						}
					}
				}

				// draw the window
				begin("Protonation Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize))

				text("Tools:")
				indent(10f)

				if (button("Clear all Hydrogens")) {
					clearAll(molViews)
				}

				unindent(10f)

				val selection = selection

				// show the selected atom
				text("Selected:")
				beginChild("selected", 300f, 30f, true)
				if (selection != null) {
					text(selection.atom.name)
				} else {
					text("(Click a heavy atom to show protonation options.)")
				}
				endChild()

				// show the nearby atoms
				if (selection != null) {

					text("Protonation states:")

					val numItems = selection.protonations.size + 1
					if (listBoxHeader("", numItems)) {

						// always add an option for no hydrogens
						if (selectable("0 H", selection.current == null)) {
							selection.set(null)
						}

						for (protonation in selection.protonations) {
							if (selectable("${protonation.numH} H, ${protonation.hybridization}", selection.current == protonation)) {
								selection.set(protonation)
							}
						}

						listBoxFooter()
					}
				}

				end()
			},
			onClose = {

				// remove the hover effect
				slidewin.hoverEffects.remove(id)

				// clear any leftover selections when the window closes
				molViews.clearSelections()
				selection = null
			}
		)
	}

	private fun clearAll(views: List<MoleculeRenderView>) {
		for (view in views) {
			view.mol.atoms
				.filter { it.element != Element.Hydrogen }
				.forEach { view.mol.deprotonate(it) }
			view.moleculeChanged()
		}
	}

	private inner class Selection(val view: MoleculeRenderView, val atom: Atom) {

		// get the protonations
		val protonations = view.mol.protonations(atom)

		var current: Protonation? = run {
			val bondedAtoms = view.mol.bonds.bondedAtoms(atom)
			val numHeavy = bondedAtoms.count { it.element != Element.Hydrogen }
			val numH = bondedAtoms.count { it.element == Element.Hydrogen }
			protonations
				.filter { it.numHeavy == numHeavy && it.numH == numH }
				// TODO: try to detect the hybridization?
				//  (so we don't have to return null when we get multiple answers)
				.takeIf { it.size == 1 }
				?.first()
		}

		init {
			// add the selection effect
			view.renderEffects[atom] = selectedEffect
		}

		fun set(protonation: Protonation?) {

			// update the selection
			if (current == protonation) {
				return
			}
			current = protonation

			// update the molecule
			if (protonation != null) {
				view.mol.protonate(atom, protonation)
			} else {
				view.mol.deprotonate(atom)
			}
			view.moleculeChanged()
		}
	}
}


private val hoverEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset, RenderEffect.Flags.Outset),
	200u, 200u, 200u
)
private val selectedEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset, RenderEffect.Flags.Outset),
	255u, 255u, 255u
)
