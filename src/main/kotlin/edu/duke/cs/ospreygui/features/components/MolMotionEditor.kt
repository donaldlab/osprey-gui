package edu.duke.cs.ospreygui.features.components

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.ByteFlags
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.ClickTracker
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.gui.infoTip
import edu.duke.cs.molscope.gui.styleEnabledIf
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.render.RenderEffect
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.features.slide.ConformationEditor
import edu.duke.cs.ospreygui.motions.DihedralAngle
import edu.duke.cs.ospreygui.motions.MolMotion
import edu.duke.cs.ospreygui.motions.TranslationRotation


class MolMotionEditor(
	val molInfo: ConformationEditor.MolInfo,
	var desc: MolMotion.Description?,
	val onClose: () -> Unit
) {

	/**
	 * Since we're editing a thing that might not exist yet (ie desc might be null),
	 * we can't use that thing to establish identity for the GUI window.
	 * So just pick an unique number to use as the identity instead.
	 * We probably won't need to edit more than ~2 billion motions in single session, right?
	 */
	companion object {
		private var nextId = 0
		fun makeId() = nextId++
	}
	private val id = makeId()

	private val winState = WindowState()
		.apply { pOpen.value = true }

	private var info: MotionInfo? = null
	private var viewer: MotionViewer? = null

	private fun Slide.Locked.findView() =
		views
			.filterIsInstance<MoleculeRenderView>()
			.find { it.mol === molInfo.mol }
			?: throw Error("can't edit molecule motion, molecule has no render view")

	fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		winState.render(
			onOpen = {

				val view = slide.findView()

				// init the motion info
				when (desc) {
					is DihedralAngle.MolDescription -> resetInfo(view, MotionInfo.DihedralAngleInfo(this@MolMotionEditor, view))
					is TranslationRotation.MolDescription -> resetInfo(view, MotionInfo.TranslationRotationInfo(this@MolMotionEditor, view))

					// start with a translation/rotation motion by default
					null -> resetInfo(view, MotionInfo.TranslationRotationInfo(this@MolMotionEditor, view)).initDefault(view)
				}
			},
			whenOpen = {

				val view = slide.findView()
				val desc = desc

				// draw the window
				window("Edit Molecule Motion##$id", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

					// show options to pick the motion type
					text("Motion type:")
					indent(20f)

					val canDihedralAngle = molInfo.mol.atoms.size >= 4
					styleEnabledIf(canDihedralAngle) {
						if (radioButton("Dihedral Angle", desc is DihedralAngle.MolDescription) && canDihedralAngle) {
							resetInfo(view, MotionInfo.DihedralAngleInfo(this@MolMotionEditor, view)).initDefault(view)
						}
					}

					if (radioButton("Translation & Rotation", desc is TranslationRotation.MolDescription)) {
						resetInfo(view, MotionInfo.TranslationRotationInfo(this@MolMotionEditor, view)).initDefault(view)
					}

					unindent(20f)

					spacing()
					spacing()
					spacing()

					// show the editing gui for the motion
					text("Settings:")
					indent(20f)
					info?.gui(imgui, slidewin, view)
					unindent(20f)

					// show the motion viewer, if possible
					val viewer = viewer
					if (viewer != null) {

						spacing()
						spacing()
						spacing()

						text("Try it out:")
						indent(20f)
						viewer.gui(imgui, view)
						unindent(20f)
					}
				}
			},
			onClose = {

				// cleanup
				val view = slide.findView()
				viewer?.reset(view)
				viewer = null
				info?.cleanup(view)
				info = null

				onClose()
			}
		)
	}

	private fun resetInfo(view: MoleculeRenderView, info: MotionInfo): MotionInfo {

		// cleanup the old info, if any
		this.info?.cleanup(view)

		// set the new info
		this.info = info

		// initialize the viewer
		resetViewer(view)

		return info
	}

	fun resetMotion(view: MoleculeRenderView, desc: MolMotion.Description) {

		val index = molInfo.motions.indexOfFirst { it === this.desc }
		if (index >= 0) {
			// if the old motion exists, replace it
			molInfo.motions[index] = desc
		} else {
			// otherwise, append to the list
			molInfo.motions.add(desc)
		}

		this.desc = desc

		resetViewer(view)
	}

	private fun resetViewer(view: MoleculeRenderView) {

		// cleanup the old viewer, if needed
		this.viewer?.reset(view)

		// start the new one
		val desc = desc
		this.viewer = when(desc) {
			is DihedralAngle.MolDescription -> DihedralAngleViewer(desc)
			is TranslationRotation.MolDescription -> TranslationRotationViewer(desc)
			else -> null
		}
	}

	private sealed class MotionInfo {

		abstract fun initDefault(view: MoleculeRenderView)
		abstract fun gui(imgui: Commands, slidewin: SlideCommands, view: MoleculeRenderView)
		open fun cleanup(view: MoleculeRenderView) {}


		class DihedralAngleInfo(val editor: MolMotionEditor, view: MoleculeRenderView) : MotionInfo() {

			companion object {
				const val defaultRadiusDegrees = 9.0
			}

			private val desc get() = editor.desc as? DihedralAngle.MolDescription

			override fun initDefault(view: MoleculeRenderView) {

				// start with arbitrary atoms, we'll change them later
				val mol = editor.molInfo.mol
				val a = mol.atoms[0]
				val b = mol.atoms[1]
				val c = mol.atoms[2]
				val d = mol.atoms[3]
				val initialDegrees = DihedralAngle.measureDegrees(a.pos, b.pos, c.pos, d.pos)

				editor.resetMotion(view, DihedralAngle.MolDescription(
					mol,
					a, b, c, d,
					initialDegrees - defaultRadiusDegrees,
					initialDegrees + defaultRadiusDegrees
				))
			}

			private val pRadiusDegrees = Ref.of((desc?.radiusDegrees ?: defaultRadiusDegrees).toFloat())
			private var currentAtomIndex = 0
			private val clickTracker = ClickTracker()

			override fun gui(imgui: Commands, slidewin: SlideCommands, view: MoleculeRenderView) = imgui.run {

				val desc = desc ?: return

				text("Angle radius (in degrees)")
				sameLine()
				infoTip("""
					|This value specifies the half-width (ie radius) of the interval of allowed angles.
				""".trimMargin())
				if (sliderFloat("##dihedralRadius", pRadiusDegrees, 0f, 180f,"%.1f")) {
					editor.resetMotion(view, DihedralAngle.MolDescription(
						desc.mol, desc.a, desc.b, desc.c, desc.d, radiusDegrees = pRadiusDegrees.value.toDouble()
					))
				}
				sameLine()
				infoTip("Ctrl-click to type a precise value")

				// atom pickers
				if (radioButton("Atom A: ${desc.a.name}###atoma", currentAtomIndex == 0)) {
					currentAtomIndex = 0
				}
				if (radioButton("Atom B: ${desc.b.name}###atomb", currentAtomIndex == 1)) {
					currentAtomIndex = 1
				}
				if (radioButton("Atom C: ${desc.c.name}###atomc", currentAtomIndex == 2)) {
					currentAtomIndex = 2
				}
				if (radioButton("Atom D: ${desc.d.name}###atomd", currentAtomIndex == 3)) {
					currentAtomIndex = 3
				}

				// are we hovering over an atom?
				val hoverAtom: Atom? =
					slidewin.mouseTarget
						?.takeIf { it.view === view }
						?.target as? Atom

				// update atom render effects
				view.renderEffects.clear()
				if (hoverAtom != null) {
					view.renderEffects[hoverAtom] = hoverEffect
				}
				view.renderEffects[desc.a] = selectedEffect
				view.renderEffects[desc.b] = selectedEffect
				view.renderEffects[desc.c] = selectedEffect
				view.renderEffects[desc.d] = selectedEffect

				// if we clicked an atom, update the motion
				if (hoverAtom != null && clickTracker.clicked(slidewin)) {
					when (currentAtomIndex) {
						0 -> editor.resetMotion(view, DihedralAngle.MolDescription(
							desc.mol, hoverAtom, desc.b, desc.c, desc.d, radiusDegrees = pRadiusDegrees.value.toDouble()
						))
						1 -> editor.resetMotion(view, DihedralAngle.MolDescription(
							desc.mol, desc.a, hoverAtom, desc.c, desc.d, radiusDegrees = pRadiusDegrees.value.toDouble()
						))
						2 -> editor.resetMotion(view, DihedralAngle.MolDescription(
							desc.mol, desc.a, desc.b, hoverAtom, desc.d, radiusDegrees = pRadiusDegrees.value.toDouble()
						))
						3 -> editor.resetMotion(view, DihedralAngle.MolDescription(
							desc.mol, desc.a, desc.b, desc.c, hoverAtom, radiusDegrees = pRadiusDegrees.value.toDouble()
						))
					}
					currentAtomIndex = (currentAtomIndex + 1) % 4
				}
			}

			override fun cleanup(view: MoleculeRenderView) {
				view.renderEffects.clear()
			}
		}

		class TranslationRotationInfo(val editor: MolMotionEditor, view: MoleculeRenderView) : MotionInfo() {

			companion object {
				const val defaultDist = 1.2 // angstroms
				const val defaultDegrees = 5.0
			}

			private val desc get() = editor.desc as? TranslationRotation.MolDescription

			override fun initDefault(view: MoleculeRenderView) {
				editor.resetMotion(view, TranslationRotation.MolDescription(
					editor.molInfo.mol,
					defaultDist,
					defaultDegrees
				))
			}

			private val pDist = Ref.of(desc?.maxTranslationDist ?: defaultDist)
			private val pDegrees = Ref.of(desc?.maxRotationDegrees ?: defaultDegrees)

			override fun gui(imgui: Commands, slidewin: SlideCommands, view: MoleculeRenderView) = imgui.run {

				val desc = editor.desc as? TranslationRotation.MolDescription ?: return

				if (inputDouble("Max Distance (Angstroms)", pDist)) {
					editor.resetMotion(view, desc.copy(maxTranslationDist = pDist.value))
				}

				if (inputDouble("Max Rotation (Degrees)", pDegrees)) {
					editor.resetMotion(view, desc.copy(maxRotationDegrees = pDegrees.value))
				}
			}
		}
	}
}

private val hoverEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset, RenderEffect.Flags.Outset),
	255u, 255u, 255u
)
private val selectedEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset, RenderEffect.Flags.Outset),
	0u, 255u, 0u
)
