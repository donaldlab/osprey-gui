package edu.duke.cs.ospreygui.dofs

import cuchaz.kludge.tools.toDegrees
import cuchaz.kludge.tools.toRadians
import edu.duke.cs.molscope.molecule.Atom
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
) {

	// grab all the atoms connected to c not through b-c
	val rotatedAtoms = mol
		.bfs(
			source = c,
			visitSource = false,
			shouldVisit = { _, to, _ -> to !== b }
		)
		.map { it.atom }
		.toList()

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

fun DesignPosition.dihedralAngle(dihedral: ConfLib.DegreeOfFreedom.DihedralAngle) =
	DihedralAngle(
		mol,
		atomResolverOrThrow.resolveOrThrow(dihedral.a),
		atomResolverOrThrow.resolveOrThrow(dihedral.b),
		atomResolverOrThrow.resolveOrThrow(dihedral.c),
		atomResolverOrThrow.resolveOrThrow(dihedral.d)
	)

fun DesignPosition.supportsDihedralAngle(dihedral: ConfLib.DegreeOfFreedom.DihedralAngle) =
	listOf(dihedral.a, dihedral.b, dihedral.c, dihedral.c)
		.all {
			atomResolver?.resolve(it) != null
		}
