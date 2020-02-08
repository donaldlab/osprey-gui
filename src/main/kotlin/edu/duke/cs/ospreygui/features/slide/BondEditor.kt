package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.*
import cuchaz.kludge.vulkan.ColorRGBA
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.*
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.render.HoverEffects
import edu.duke.cs.molscope.render.MoleculeRenderEffects
import edu.duke.cs.molscope.render.RenderEffect
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.forcefield.amber.inferBondsAmber
import edu.duke.cs.ospreygui.prep.BondGuesser
import edu.duke.cs.ospreygui.prep.covalentRange
import edu.duke.cs.ospreygui.prep.toTree
import java.util.*


class BondEditor : SlideFeature {

	override val id = FeatureId("edit.bonds")

	private val winState = WindowState()
	private val clickTracker = ClickTracker()
	private var hoverEffects = null as HoverEffects.Writer?
	private val renderEffects = IdentityHashMap<MoleculeRenderView,MoleculeRenderEffects.Writer>()

	private val pMaxDist = Ref.of(3f)

	private var selection: Selection? = null


	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Bonds")) {
			winState.isOpen = true
		}
	}

	private fun Slide.Locked.molViews() = views.mapNotNull { it as? MoleculeRenderView }

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		val views = slide.molViews()

		winState.render(
			onOpen = {

				// add the hover effect
				hoverEffects = slidewin.hoverEffects.writer().apply {
					effect = hoverEffect
				}

				// init the render effects for each molecule
				for (view in views) {
					renderEffects[view] = view.renderEffects.writer()
				}
			},
			whenOpen = {

				// did we click anything?
				if (clickTracker.clicked(slidewin)) {

					selection = null
					renderEffects.values.forEach { it.clear() }

					// select the atom from the click, if any
					slidewin.mouseTarget?.let { target ->
						(target.view as? MoleculeRenderView)?.let { view ->
							(target.target as? Atom)?.let { atom ->
								selectAtom(view, atom)
							}
						}
					}
				}

				// draw the window
				window("Bond Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

					text("Tools:")
					indent(10f)

					if (button("Clear all bonds")) {
						clearBonds(views)
					}

					if (button("Add bonds automatically")) {
						slidewin.showExceptions {
							guessBonds(views)
						}
					}
					sameLine()
					infoTip("""
						|This tool infers atom connectivity using a molecular mechanics forcefield.
						|After bonds have been added automatically, feel free to use
						|the fine-grained editing tools to add any missing bonds, or
						|remove any extraneous bonds.
					""".trimMargin())

					unindent(10f)

					val selection = selection

					// show the selected atom
					text("Selected:")
					child("selected", 300f, 30f, true) {
						if (selection != null) {
							text(selection.atom.name)
						} else {
							text("(Click an atom to show bonding options.)")
						}
					}

					// show the nearby atoms
					text("Nearby Atoms:")
					child("nearby", 300f, 300f, true) {
						if (selection != null) {

							columns(2)
							for (info in selection.nearbyAtoms) {

								// show a checkbox to toggle the bond on/off
								val isCheckHovered = renderBondCheck(imgui, info)
								nextColumn()
								renderBondLabel(imgui, info)
								nextColumn()

								// update atom selections
								renderEffects[selection.view]?.set(info.atom, if (isCheckHovered) {
									// highlight the atom when we mouseover the checkbox
									hoverEffect
								} else {
									// otherwise, color by range
									if (info.dist in info.covalentRange) {
										inRangeEffect
									} else {
										outOfRangeEffect
									}
								})
							}
							columns(1)
						}
					}
				}
			},
			onClose = {

				// remove the hover effect
				hoverEffects?.close()
				hoverEffects = null

				// clear any leftover selections when the window closes
				renderEffects.values.forEach { it.close() }
				renderEffects.clear()
			}
		)
	}

	override fun contextMenu(contextMenu: ContextMenu, slide: Slide.Locked, slidewin: SlideCommands, target: ViewIndexed) {

		if (!winState.isOpen) {
			return
		}

		// get the view and atom, if any
		val view = target.view as? MoleculeRenderView ?: return
		val atom = target.target as? Atom ?: return

		contextMenu.add {

			// show a button to select the atom
			if (button("Select Atom")) {
				closeCurrentPopup()
				renderEffects.values.forEach { it.clear() }
				selectAtom(view, atom)
			}

			selection?.let { selection ->
				if (selection.atom != atom) {

					// get the atom info (reuse a nearby atom if possible)
					val info = selection.nearbyAtoms
						.find { it.atom == atom }
						?: selection.AtomInfo(atom)

					// show a checkbox to toggle the bond on/off
					renderBondCheck(this, info)
					sameLine()
					renderBondLabel(this, info)
				}
			}
		}
	}

	private fun renderBondCheck(imgui: Commands, info: Selection.AtomInfo): Boolean = imgui.run {

		// add a checkbox to toggle the bond
		if (checkbox(info.atom.name, info.pBonded)) {
			info.updateBond()
		}
		return isItemHovered()
	}

	private fun renderBondLabel(imgui: Commands, info: Selection.AtomInfo) = imgui.run {

		// show the distance
		textColored(
			if (info.dist in info.covalentRange) {
				ColorRGBA.Int(0, 255, 0)
			} else {
				ColorRGBA.Int(255, 0, 0)
			},
			"${"%.2f".format(info.dist)} A"
		)
		if (isItemHovered()) {
			info.covalentRange.let { range ->
				setTooltip("%.2f A %s in range [%.2f A, %.2f A]".format(
					info.dist,
					if (info.dist in info.covalentRange) {
						"is"
					} else {
						"is not"
					},
					range.start,
					range.endInclusive
				))
			}
		}
	}

	private fun selectAtom(view: MoleculeRenderView, atom: Atom) {

		// make the selection for that atom
		selection = Selection(
			view,
			atom,
			pMaxDist.value.toDouble().square()
		).apply {

			// highlight the selected atom
			renderEffects[view]?.set(atom, selectedEffect)
		}
	}

	private fun clearBonds(views: List<MoleculeRenderView>) {
		for (view in views) {
			view.mol.bonds.clear()
			view.moleculeChanged()
		}
	}

	private fun guessBonds(views: List<MoleculeRenderView>) {
		for (view in views) {
			for ((a1, a2) in view.mol.inferBondsAmber()) {
				view.mol.bonds.add(a1, a2)
			}
			view.moleculeChanged()
		}
	}
}


private class Selection(val view: MoleculeRenderView, val atom: Atom, val maxDistSq: Double) {

	val mol get() = view.mol
	val bondGuesser = BondGuesser()

	inner class AtomInfo(val atom: Atom, val dist: Double) {

		constructor(atom: Atom) : this(
			atom,
			this@Selection.atom.pos.distance(atom.pos)
		)

		val pBonded = Ref.of(view.mol.bonds.isBonded(this@Selection.atom, atom))

		val covalentRange = atom.covalentRange(this@Selection.atom, bondGuesser)

		fun updateBond() {
			val a1 = this@Selection.atom
			val a2 = this@AtomInfo.atom
			if (pBonded.value) {
				mol.bonds.add(a1, a2)
			} else {
				mol.bonds.remove(a1, a2)
			}
			view.moleculeChanged()
		}
	}

	val nearbyAtoms = mol.atoms.toTree().nearest(atom).asSequence()
		.takeWhile { (_, distSq) -> distSq <= maxDistSq }
		.filter { (nearbyAtom, _) -> atom != nearbyAtom }
		.map { (nearbyAtom, distSq) -> AtomInfo(nearbyAtom, distSq.sqrt()) }
		.toList()
}

private val hoverEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset, RenderEffect.Flags.Outset),
	200u, 200u, 200u
)
private val selectedEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset, RenderEffect.Flags.Outset),
	255u, 255u, 255u
)
private val inRangeEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset),
	0u, 255u, 0u
)
private val outOfRangeEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset),
	255u, 0u, 0u
)
