package edu.duke.cs.ospreygui.compiler

import org.joml.Vector3d


class CompiledConfSpace(
	val name: String,
	val forcefields: List<ForcefieldInfo>,
	val staticAtoms: List<AtomInfo>,
	/** in the same order as the forcefields */
	val staticEnergies: List<Double>,
	val positions: List<PosInfo>,
	/** in the same order as the forcefields */
	val atomPairs: List<AtomPairs>
) {

	data class ForcefieldInfo(
		val name: String,
		val ospreyImplementation: String,
		val settings: List<Pair<String,Any>>
	)

	data class PosInfo(
		val name: String,
		val confs: List<ConfInfo>
	)

	data class ConfInfo(
		val id: String,
		val type: String,
		val atoms: List<AtomInfo>,
		val motions: List<MotionInfo>,
		/** in the same order as the forcefields */
		val internalEnergies: List<Double>
	)

	data class AtomInfo(
		val name: String,
		val pos: Vector3d
	)

	sealed class MotionInfo {

		data class DihedralAngle(
			val minDegrees: Double,
			val maxDegrees: Double,
			val abcd: List<Int>,
			val rotated: List<Int>
		) : MotionInfo()
	}
}
