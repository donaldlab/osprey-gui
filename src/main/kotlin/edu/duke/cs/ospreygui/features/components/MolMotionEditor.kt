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

					// make info for an existing motion
					is DihedralAngle.MolDescription -> resetInfo(view, MotionInfo.DihedralAngleInfo(this@MolMotionEditor, slidewin, view))
					is TranslationRotation.MolDescription -> resetInfo(view, MotionInfo.TranslationRotationInfo(this@MolMotionEditor))

					// by default, make a new translation/rotation motion and its info
					null -> resetInfo(view, MotionInfo.TranslationRotationInfo(this@MolMotionEditor)).initDefault(view)
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
							resetInfo(view, MotionInfo.DihedralAngleInfo(this@MolMotionEditor, slidewin, view)).initDefault(view)
						}
					}

					if (radioButton("Translation & Rotation", desc is TranslationRotation.MolDescription)) {
						resetInfo(view, MotionInfo.TranslationRotationInfo(this@MolMotionEditor)).initDefault(view)
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
				info?.close()
				info = null

				onClose()
			}
		)
	}

	private fun resetInfo(view: MoleculeRenderView, info: MotionInfo): MotionInfo {

		// cleanup the old info, if any
		this.info?.close()

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

	private sealed class MotionInfo : AutoCloseable {

		abstract fun initDefault(view: MoleculeRenderView)
		abstract fun gui(imgui: Commands, slidewin: SlideCommands, view: MoleculeRenderView)

		override fun close() {
			// nothing to cleanup by default
		}

		class DihedralAngleInfo(val editor: MolMotionEditor, slidewin: SlideCommands, view: MoleculeRenderView) : MotionInfo() {

			companion object {
				const val defaultRadiusDegrees = 9.0
			}

			private val desc get() = editor.desc as? DihedralAngle.MolDescription

			// init effects
			private val hoverEffects = slidewin.hoverEffects.writer()
			private val renderEffects = view.renderEffects.writer()

			init {
				resetEffects()
			}

			private fun resetEffects() {
				renderEffects.clear()
				desc?.let { desc ->
					renderEffects[desc.a] = selectedEffect
					renderEffects[desc.b] = selectedEffect
					renderEffects[desc.c] = selectedEffect
					renderEffects[desc.d] = selectedEffect
				}
			}

			// cleanup effects
			override fun close() {
				hoverEffects.close()
				renderEffects.close()
			}

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

				resetEffects()
			}

			private enum class AtomSel {

				A, B, C, D;

				fun next() =
					values()[(ordinal + 1) % 4]
			}

			private val pRadiusDegrees = Ref.of((desc?.radiusDegrees ?: defaultRadiusDegrees).toFloat())
			private var currentAtom = AtomSel.A
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
				if (radioButton("Atom A: ${desc.a.name}###atoma", currentAtom == AtomSel.A)) {
					currentAtom = AtomSel.A
				}
				if (radioButton("Atom B: ${desc.b.name}###atomb", currentAtom == AtomSel.B)) {
					currentAtom = AtomSel.B
				}
				if (radioButton("Atom C: ${desc.c.name}###atomc", currentAtom == AtomSel.C)) {
					currentAtom = AtomSel.C
				}
				if (radioButton("Atom D: ${desc.d.name}###atomd", currentAtom == AtomSel.D)) {
					currentAtom = AtomSel.D
				}

				// are we hovering over an atom?
				val hoverAtom: Atom? =
					slidewin.mouseTarget
						?.takeIf { it.view === view }
						?.target as? Atom

				// if we clicked an atom, update the motion
				if (hoverAtom != null && clickTracker.clicked(slidewin)) {
					val newdesc = when (currentAtom) {
						AtomSel.A -> DihedralAngle.MolDescription(
							desc.mol, hoverAtom, desc.b, desc.c, desc.d, radiusDegrees = pRadiusDegrees.value.toDouble()
						)
						AtomSel.B -> DihedralAngle.MolDescription(
							desc.mol, desc.a, hoverAtom, desc.c, desc.d, radiusDegrees = pRadiusDegrees.value.toDouble()
						)
						AtomSel.C -> DihedralAngle.MolDescription(
							desc.mol, desc.a, desc.b, hoverAtom, desc.d, radiusDegrees = pRadiusDegrees.value.toDouble()
						)
						AtomSel.D -> DihedralAngle.MolDescription(
							desc.mol, desc.a, desc.b, desc.c, hoverAtom, radiusDegrees = pRadiusDegrees.value.toDouble()
						)
					}
					editor.resetMotion(view, newdesc)
					currentAtom = currentAtom.next()
					resetEffects()
				}
			}
		}

		class TranslationRotationInfo(val editor: MolMotionEditor) : MotionInfo() {

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
