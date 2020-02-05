package edu.duke.cs.ospreygui.motions

import cuchaz.kludge.tools.toDegrees
import cuchaz.kludge.tools.toRadians
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.tools.normalizeMinusPIToPI
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.prep.DesignPosition
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.PI
import kotlin.math.atan2


class DihedralAngle(
	val mol: Molecule,
	val a: Atom,
	val b: Atom,
	val c: Atom,
	val d: Atom
) : ConfMotion, MolMotion {

	data class LibrarySettings(
		var radiusDegrees: Double,
		var includeHydroxyls: Boolean,
		/** eg Methyls, Methylenes */
		var includeNonHydroxylHGroups: Boolean
	)

	class ConfDescription(
		val pos: DesignPosition,
		val motion: ConfLib.ContinuousMotion.DihedralAngle,
		val minDegrees: Double,
		val maxDegrees: Double
	) : ConfMotion.Description {

		override fun copyTo(pos: DesignPosition) =
			ConfDescription(
				pos,
				motion,
				minDegrees,
				maxDegrees
			)

		override fun make() =
			DihedralAngle(
				pos.mol,
				pos.atomResolverOrThrow.resolveOrThrow(motion.a),
				pos.atomResolverOrThrow.resolveOrThrow(motion.b),
				pos.atomResolverOrThrow.resolveOrThrow(motion.c),
				pos.atomResolverOrThrow.resolveOrThrow(motion.d)
			)

		companion object {

			fun make(pos: DesignPosition, motion: ConfLib.ContinuousMotion.DihedralAngle, conf: ConfLib.Conf, radiusDegrees: Double): ConfDescription {

				val initialDegrees = measureDegrees(
					motion.a.resolveCoordsOrThrow(conf),
					motion.b.resolveCoordsOrThrow(conf),
					motion.c.resolveCoordsOrThrow(conf),
					motion.d.resolveCoordsOrThrow(conf)
				)

				return ConfDescription(
					pos,
					motion,
					minDegrees = initialDegrees - radiusDegrees,
					maxDegrees = initialDegrees + radiusDegrees
				)
			}

			fun makeFromLibrary(pos: DesignPosition, frag: ConfLib.Fragment, conf: ConfLib.Conf, settings: LibrarySettings) =
				frag.motions
					.filterIsInstance<ConfLib.ContinuousMotion.DihedralAngle>()
					.mapNotNull desc@{ motion ->

						val match = pos.findAnchorMatch(frag)
							?: throw RuntimeException("no anchor match")

						// filter out H-group rotations if needed
						val rotatedAtoms = motion.affectedAtoms(frag)
						val isHGroup = rotatedAtoms.isNotEmpty() && rotatedAtoms.all { it.element == Element.Hydrogen }
						val isHydroxyl = isHGroup && rotatedAtoms.size == 1 && match.resolveElementOrThrow(motion.c) == Element.Oxygen
						val isNonHydroxylHGroup = isHGroup && !isHydroxyl

						if (isHydroxyl && !settings.includeHydroxyls) {
							return@desc null
						}
						if (isNonHydroxylHGroup && !settings.includeNonHydroxylHGroups) {
							return@desc null
						}

						return@desc make(pos, motion, conf, settings.radiusDegrees)
					}
		}
	}

	class MolDescription(
		val mol: Molecule,
		val a: Atom,
		val b: Atom,
		val c: Atom,
		val d: Atom,
		val minDegrees: Double,
		val maxDegrees: Double
	) : MolMotion.Description {

		val rotatedAtoms = findRotatedAtoms(mol, b, c)

		override fun copyTo(mol: Molecule): MolDescription {
			val map = this.mol.mapAtomsByValueTo(mol)
			return MolDescription(
				mol,
				map.getBOrThrow(a),
				map.getBOrThrow(b),
				map.getBOrThrow(c),
				map.getBOrThrow(d),
				minDegrees,
				maxDegrees
			)
		}

		override fun make() =
			DihedralAngle(mol, a, b, c, d)

		override fun getAffectedAtoms() = rotatedAtoms

		companion object {

			fun make(mol: Molecule, a: Atom, b: Atom, c: Atom, d: Atom, radiusDegrees: Double): MolDescription {

				val initialDegrees = measureDegrees(a.pos, b.pos, c.pos, d.pos)

				return MolDescription(
					mol,
					a,
					b,
					c,
					d,
					minDegrees = initialDegrees - radiusDegrees,
					maxDegrees = initialDegrees + radiusDegrees
				)
			}
		}
	}

	val rotatedAtoms = findRotatedAtoms(mol, b, c)

	/**
	 * Returns the dihedral angle in degrees in the interval (-180,180]
	 */
	fun measureDegrees() =
		measureRadians().toDegrees()

	/**
	 * Returns the dihedral angle in radians in the interval (-pi,pi]
	 */
	fun measureRadians() =
		measureRadians(a.pos, b.pos, c.pos, d.pos)

	fun setDegrees(degrees: Double) =
		setRadians(degrees.toRadians())

	fun setRadians(radians: Double) {

		val a = Vector3d(a.pos)
		val c = Vector3d(c.pos)
		val d = Vector3d(d.pos)

		// translate so b is at the origin
		b.pos.let { t ->
			a.sub(t)
			c.sub(t)
			d.sub(t)
			rotatedAtoms.forEach { it.pos.sub(t) }
		}

		// rotate into a coordinate system where:
		//   b->c is along the -z axis
		//   b->a is in the yz plane
		val rotation = Quaterniond().lookAlong(c, a)
		d.rotate(rotation)
		rotatedAtoms.forEach { it.pos.rotate(rotation) }

		// rotate about z to set the desired dihedral angle
		Quaterniond()
			.rotationZ(PI/2 - radians - atan2(d.y, d.x))
			.let { q ->
				rotatedAtoms.forEach { it.pos.rotate(q) }
			}

		// rotate back into the world frame
		rotation.conjugate()
		rotatedAtoms.forEach { it.pos.rotate(rotation) }

		// translate back to b
		b.pos.let { t ->
			rotatedAtoms.forEach { it.pos.add(t) }
		}
	}

	companion object {

		/** grab all the atoms connected to c not through b-c */
		fun findRotatedAtoms(mol: Molecule, b: Atom, c: Atom) =
			mol
				.bfs(
					source = c,
					visitSource = false,
					shouldVisit = { _, to, _ -> to !== b }
				)
				.map { it.atom }
				.toList()

		/**
		 * Returns the dihedral angle in radians in the interval (-180,180]
		 */
		fun measureDegrees(a: Vector3dc, b: Vector3dc, c: Vector3dc, d: Vector3dc) =
			measureRadians(a, b, c, d).toDegrees()

		/**
		 * Returns the dihedral angle in radians in the interval (-pi,pi]
		 */
		fun measureRadians(a: Vector3dc, b: Vector3dc, c: Vector3dc, d: Vector3dc): Double {

			// make mutable copies of the positions
			@Suppress("NAME_SHADOWING")
			val a = Vector3d(a)
			@Suppress("NAME_SHADOWING")
			val c = Vector3d(c)
			@Suppress("NAME_SHADOWING")
			val d = Vector3d(d)

			// translate so b is at the origin
			b.let {
				a.sub(it)
				c.sub(it)
				d.sub(it)
			}

			// rotate into a coordinate system where:
			//   b->c is along the -z axis
			//   b->a is in the yz plane
			Quaterniond()
				.lookAlong(c, a)
				.let {
					d.rotate(it)
				}

			return (PI/2 - atan2(d.y, d.x))
				.normalizeMinusPIToPI()
		}
	}
}
