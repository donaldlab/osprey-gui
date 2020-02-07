package edu.duke.cs.ospreygui.features.components

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.Ref
import cuchaz.kludge.tools.toRadians
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.motions.TranslationRotation
import edu.duke.cs.ospreygui.tools.nextFloatIn
import kotlin.random.Random


class TranslationRotationViewer(
	val transRot: TranslationRotation,
	val maxTranslationDist: Float,
	val maxRotationDegrees: Float
) : MotionViewer {

	constructor(desc: TranslationRotation.MolDescription) : this(
		desc.make(),
		desc.maxTranslationDist.toFloat(),
		desc.maxRotationDegrees.toFloat()
	)

	private val pPsi = Ref.of(0.0f)
	private val pTheta = Ref.of(0.0f)
	private val pPhi = Ref.of(0.0f)
	private val px = Ref.of(0.0f)
	private val py = Ref.of(0.0f)
	private val pz = Ref.of(0.0f)

	private val rmax = maxRotationDegrees
	private val rmin = -rmax
	private val tmax = maxTranslationDist
	private val tmin = -tmax

	override val label = "Translation and Rotation of ${transRot.mol}"

	private fun updateMol(view: MoleculeRenderView) {
		transRot.set(
			pPsi.value.toDouble().toRadians(),
			pTheta.value.toDouble().toRadians(),
			pPhi.value.toDouble().toRadians(),
			px.value.toDouble(),
			py.value.toDouble(),
			pz.value.toDouble()
		)
		view.moleculeChanged()
	}

	override fun gui(imgui: Commands, view: MoleculeRenderView) = imgui.run {

		text("Tait-Bryan Rotation:")
		if (sliderFloat("Psi (X)", pPsi, rmin, rmax, "%.3f")) {
			updateMol(view)
		}
		if (sliderFloat("Theta (Y)", pTheta, rmin, rmax, "%.3f")) {
			updateMol(view)
		}
		if (sliderFloat("Phi (Z)", pPhi, rmin, rmax, "%.3f")) {
			updateMol(view)
		}

		text("Cartesian Translation:")
		if (sliderFloat("X", px, tmin, tmax, "%.3f")) {
			updateMol(view)
		}
		if (sliderFloat("Y", py, tmin, tmax, "%.3f")) {
			updateMol(view)
		}
		if (sliderFloat("Z", pz, tmin, tmax, "%.3f")) {
			updateMol(view)
		}
	}

	override fun jiggle(rand: Random, view: MoleculeRenderView) {

		pPsi.value = rand.nextFloatIn(rmin, rmax)
		pTheta.value = rand.nextFloatIn(rmin, rmax)
		pPhi.value = rand.nextFloatIn(rmin, rmax)

		px.value = rand.nextFloatIn(tmin, tmax)
		py.value = rand.nextFloatIn(tmin, tmax)
		pz.value = rand.nextFloatIn(tmin, tmax)

		updateMol(view)
	}

	override fun reset(view: MoleculeRenderView) {
		transRot.reset()
		view.moleculeChanged()
	}
}
