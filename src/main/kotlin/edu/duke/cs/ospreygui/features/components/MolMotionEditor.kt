package edu.duke.cs.ospreygui.features.components

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.gui.styleEnabledIf
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.features.slide.ConformationEditor
import edu.duke.cs.ospreygui.motions.DihedralAngle
import edu.duke.cs.ospreygui.motions.MolMotion
import edu.duke.cs.ospreygui.motions.TranslationRotation


class MolMotionEditor(
	var molInfo: ConformationEditor.MolInfo,
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

				// make a default motion, if needed
				if (desc == null) {
					initTranslationRotation(view)
				}

				// initialize the viewer
				resetViewer(view)
			},
			whenOpen = {

				val view = slide.findView()
				val desc = desc

				// draw the window
				window("Edit Molecule Motion##$id", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

					text("Motion type:")
					indent(20f)

					val canDihedralAngle = molInfo.mol.atoms.size >= 4
					styleEnabledIf(canDihedralAngle) {
						if (radioButton("Dihedral Angle", desc is DihedralAngle.MolDescription) && canDihedralAngle) {
							initDihedralAngle(view)
						}
					}

					if (radioButton("Translation & Rotation", desc is TranslationRotation.MolDescription)) {
						initTranslationRotation(view)
					}

					unindent(20f)

					spacing()
					spacing()
					spacing()

					text("Settings:")
					indent(20f)
					when (desc) {
						is DihedralAngle.MolDescription -> guiDihedralAngle(imgui, desc, view)
						is TranslationRotation.MolDescription -> guiTranslationRotation(imgui, desc, view)
					}
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
				onClose()
			}
		)
	}

	private fun resetMotion(view: MoleculeRenderView, desc: MolMotion.Description) {

		// remove the old motion, if any
		this.desc?.let { molInfo.motions.remove(it) }

		// add the new one
		molInfo.motions.add(desc)

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

	private fun initDihedralAngle(view: MoleculeRenderView) {

		// start with arbitrary atoms, we'll change them later
		val a = molInfo.mol.atoms[0]
		val b = molInfo.mol.atoms[1]
		val c = molInfo.mol.atoms[2]
		val d = molInfo.mol.atoms[3]
		val initialDegrees = DihedralAngle.measureDegrees(a.pos, b.pos, c.pos, d.pos)
		val defaultRadius = 9.0

		// create a new dihedral angle with default settings
		resetMotion(view, DihedralAngle.MolDescription(
			molInfo.mol,
			a, b, c, d,
			initialDegrees - defaultRadius,
			initialDegrees + defaultRadius
		))
	}

	private fun guiDihedralAngle(imgui: Commands, desc: DihedralAngle.MolDescription, view: MoleculeRenderView) = imgui.run {
		// TODO
	}

	private fun initTranslationRotation(view: MoleculeRenderView) {

		// create new translation/rotation with default settings
		resetMotion(view, TranslationRotation.MolDescription(
			molInfo.mol,
			maxTranslationDist = 1.2, // angstroms
			maxRotationDegrees = 5.0
		))
	}

	private fun guiTranslationRotation(imgui: Commands, desc: TranslationRotation.MolDescription, view: MoleculeRenderView) = imgui.run {

		inputDouble("Max Distance (Angstroms)", Ref.of(
			getter = { desc.maxTranslationDist },
			setter = { newVal ->
				resetMotion(view, TranslationRotation.MolDescription(
					molInfo.mol,
					newVal,
					desc.maxRotationDegrees
				))
			}
		))

		inputDouble("Max Rotation (Degrees)", Ref.of(
			getter = { desc.maxRotationDegrees },
			setter = { newVal ->
				resetMotion(view, TranslationRotation.MolDescription(
					molInfo.mol,
					desc.maxTranslationDist,
					newVal
				))
			}
		))
	}
}
