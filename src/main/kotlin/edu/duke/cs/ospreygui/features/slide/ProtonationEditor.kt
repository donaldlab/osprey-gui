package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.*
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.ClickTracker
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.gui.infoTip
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.render.RenderEffect
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.osprey.dof.DihedralRotation
import edu.duke.cs.osprey.tools.Protractor
import edu.duke.cs.ospreygui.forcefield.amber.*
import org.joml.Vector3d
import kotlin.math.cos
import kotlin.math.sin


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

				// TODO: Reduce

				if (button("Add Hydrogens automatically")) {
					slidewin.showExceptions {
						autoProtonate(molViews)
					}
				}
				sameLine()
				infoTip("""
					|This tool adds Hydrogen atoms to heavy atoms based on inferred
					|forcefield atom types and bond types. Atom and bond type inference
					|on unprotonated molecules is very error-prone, so feel free to use
					|the fine-grained editing tools to add any missing Hydrogen atoms,
					|removea any extraneous Hydrogen atoms, or change hybridizations and geometry.
				""".trimMargin())

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

					// show a list box to pick the protonation state
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

					// if hydrogens are rotatable, show a slider to pick the dihedral angle
					if (selection.rotator?.rotationH != null) {
						val rotator = selection.rotator

						spacing()

						text("Rotation")
						if (sliderFloat("Dihedral angle", rotator.pDihedral, -180f, 180f, "%.1f")) {
							rotator.set()
						}
						sameLine()
						infoTip("Ctrl-click to type angle exactly")

						if (rotator.rotationHeavies.size > 1) {
							if (listBoxHeader("Anchor Heavy Atom", rotator.rotationHeavies.size)) {
								for (atom in rotator.rotationHeavies) {
									if (selectable(atom.name, rotator.rotationHeavy == atom)) {
										rotator.rotationHeavy = atom
									}
								}
								listBoxFooter()
							}
						}
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

	private fun autoProtonate(views: List<MoleculeRenderView>) {
		for (view in views) {
			for ((heavyAtom, hAtom) in view.mol.inferProtonation()) {
				view.mol.apply {
					atoms.add(hAtom)
					bonds.add(heavyAtom, hAtom)
				}
				view.moleculeChanged()
			}
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

		inner class Rotator(val bondedHeavy: Atom, val rotationHeavies: List<Atom>) {

			var rotationHeavy: Atom = rotationHeavies.first()
				set(value) {
					field = value
					update()
					updateSelectionEffects()
				}

			var rotationH: Atom? = pickHydrogen()

			// pick the hydrogen atom to define the dihedral angle
			private fun pickHydrogen() =
				view.mol.bonds.bondedAtoms(atom)
					.filter { it.element == Element.Hydrogen }
					.minBy { it.toInt() }

			val pDihedral = Ref.of(measureDihedral())

			private fun measureDihedral(): Float {
				val rotationH = rotationH ?: return 0f
				return measureDihedral(
					rotationHeavy,
					bondedHeavy,
					atom,
					rotationH
				).toFloat()
			}

			fun Atom.toInt() = name.filter { it.isDigit() }.toInt()

			fun Vector3d.toArray() = doubleArrayOf(x, y, z)
			fun Vector3d.fromArray(array: DoubleArray) = set(array[0], array[1], array[2])

			fun measureDihedral(a: Atom, b: Atom, c: Atom, d: Atom) =
				Protractor.measureDihedral(arrayOf(
					a.pos.toArray(),
					b.pos.toArray(),
					c.pos.toArray(),
					d.pos.toArray()
				))

			fun update() {

				// update the rotation hydrogen
				rotationH = pickHydrogen()

				// measure the current dihedral angle
				pDihedral.value = measureDihedral()
			}

			fun set() {

				// compute the target dihedral
				val (targetSin, targetCos) = pDihedral.value.toDouble().toRadians()
					.let { sin(it) to cos(it) }

				// measure the current dihedral
				val (currentSin, currentCos) = measureDihedral().toDouble().toRadians()
					.let { sin(it) to cos(it) }

				// calc the dihedral rotation as a rigid body transformation relative to the current pose
				val dsin = targetSin*currentCos - targetCos*currentSin
				val dcos = targetCos*currentCos + targetSin*currentSin
				val rotation = DihedralRotation(
					bondedHeavy.pos.toArray(),
					atom.pos.toArray(),
					dsin, dcos
				)

				// rotate all the hydrogens
				view.mol.bonds.bondedAtoms(atom)
					.filter { it.element == Element.Hydrogen }
					.forEach { h ->
						val coords = h.pos.toArray()
						rotation.transform(coords, 0)
						h.pos.fromArray(coords)
					}

				view.moleculeChanged()
			}
		}

		// if the atom has only one bonded heavy atom,
		// and that heavy atom has at least one other bonded heavy atom,
		// then the hydrogens are rotatable
		val rotator: Rotator? = run {

			val bondedHeavies = view.mol.bonds.bondedAtoms(atom)
				.filter { it.element != Element.Hydrogen }
			if (bondedHeavies.size == 1) {
				val bondedHeavy = bondedHeavies.first()

				val rotationHeavies = view.mol.bonds.bondedAtoms(bondedHeavy)
					.filter { it.element != Element.Hydrogen && it != atom }
				if (rotationHeavies.isNotEmpty()) {
					return@run Rotator(bondedHeavy, rotationHeavies)
				}
			}

			return@run null
		}

		init {
			updateSelectionEffects()
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

			if (rotator != null) {
				rotator.update()
			}

			updateSelectionEffects()
		}

		private fun updateSelectionEffects() {

			view.renderEffects.clear()

			// add the selection effect
			view.renderEffects[atom] = selectedEffect

			// highlight the dihedral atoms if needed
			rotator?.rotationH?.let { rotationH ->
				view.renderEffects[listOf(
					rotator.rotationHeavy,
					rotator.bondedHeavy,
					atom,
					rotationH
				)] = rotationEffect
			}
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
private val rotationEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Inset),
	100u, 200u, 100u
)
