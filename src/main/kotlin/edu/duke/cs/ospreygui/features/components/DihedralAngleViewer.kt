package edu.duke.cs.ospreygui.features.components

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.Ref
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.motions.DihedralAngle
import edu.duke.cs.ospreygui.tools.nextFloatIn
import org.joml.Vector3d
import kotlin.random.Random


class DihedralAngleViewer(
	val dihedral: DihedralAngle,
	val minDegrees: Float,
	val maxDegrees: Float
) : MotionViewer {

	constructor(desc: DihedralAngle.ConfDescription) : this(
		desc.make(),
		desc.minDegrees.toFloat(),
		desc.maxDegrees.toFloat()
	)
	constructor(desc: DihedralAngle.MolDescription) : this(
		desc.make(),
		desc.minDegrees.toFloat(),
		desc.maxDegrees.toFloat()
	)

	val radius = (maxDegrees - minDegrees)/2f

	// start in the center of the interval
	val pValue = Ref.of((minDegrees + maxDegrees)/2f)

	// copy the original coordinates
	val coords = dihedral.rotatedAtoms.map { Vector3d(it.pos) }

	override val label =
		"Dihedral Angle: ${listOf(dihedral.a,  dihedral.b, dihedral.c, dihedral.d).joinToString(", ") { it.name }}"

	override fun gui(imgui: Commands, view: MoleculeRenderView) = imgui.run {

		// show a slider to manipulate the dihedral angle
		text("Radius: %.1f degrees".format(radius))
		text("Range: %.1f to %.1f degrees".format(minDegrees, maxDegrees))
		if (sliderFloat("Angle", pValue, minDegrees, maxDegrees, format = "%.1f")) {
			dihedral.setDegrees(pValue.value.toDouble())
			view.moleculeChanged()
		}
	}

	override fun jiggle(rand: Random, view: MoleculeRenderView) {
		pValue.value = rand.nextFloatIn(minDegrees, maxDegrees)
		dihedral.setDegrees(pValue.value.toDouble())
		view.moleculeChanged()
	}

	override fun reset(view: MoleculeRenderView) {
		// restore the original coordinates
		for (i in coords.indices) {
			dihedral.rotatedAtoms[i].pos.set(coords[i])
		}
		view.moleculeChanged()
	}
}
