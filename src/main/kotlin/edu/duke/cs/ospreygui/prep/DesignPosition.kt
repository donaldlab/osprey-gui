package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.molecule.toIdentitySet
import edu.duke.cs.molscope.tools.normalizeZeroToTwoPI
import edu.duke.cs.ospreygui.io.ConfLib
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.*
import kotlin.NoSuchElementException
import kotlin.math.abs
import kotlin.math.atan2


class DesignPosition(
	var name: String,
	val mol: Molecule
) {

	/**
	 * Anchor atoms are used to bond and align conformations to the molecule.
	 */
	val anchorGroups: MutableList<MutableList<Anchor>> = ArrayList()

	/**
	 * Returns true iff all the fragment's anchors are compatible with this design position.
	 */
	fun isFragmentCompatible(frag: ConfLib.Fragment) =
		findAnchorMatch(frag) != null

	data class AnchorMatch(
		val posAnchors: List<Anchor>,
		val fragAnchors: List<ConfLib.Anchor>
	) {
		val pairs = posAnchors.zip(fragAnchors)
	}

	fun findAnchorMatch(frag: ConfLib.Fragment): AnchorMatch? =
		// check anchor types and order
		anchorGroups
			.firstOrNull { posAnchors ->

				// we should have the same number of anchors
				posAnchors.size == frag.anchors.size

				// check anchor types and order
				&& posAnchors
					.zip(frag.anchors)
					.all { (posAnchor, fragAnchor) ->
						posAnchor.isAnchorCompatible(fragAnchor)
					}

			}
			?.let { posAnchors ->
				AnchorMatch(posAnchors, frag.anchors)
			}

	class IncompatibleAnchorsException(val pos: DesignPosition, val frag: ConfLib.Fragment) :
		RuntimeException("design position ${pos.name} anchors are not compatible with fragment ${frag.id} anchors")

	/**
	 * Removes the current atoms from the molecule, including associated bonds.
	 * Then adds the mutated atoms to the molecule, adding bonds and aligning to the anchor as necessary.
	 *
	 * If the molecule is a polymer, the new atoms are added to the residues of their anchor atoms
	 */
	fun setConf(frag: ConfLib.Fragment, conf: ConfLib.Conf) {

		// find the anchor match
		val anchorMatch = findAnchorMatch(frag)
			?: throw IncompatibleAnchorsException(this, frag)

		// remove the existing atoms
		for (posAnchor in anchorMatch.posAnchors) {
			for (atom in posAnchor.attachedAtoms) {
				mol.atoms.remove(atom)
				if (mol is Polymer) {
					mol.findResidue(atom)?.atoms?.remove(atom)
				}
			}
			posAnchor.attachedAtoms.clear()
		}

		// copy the atoms from the conf and add them to the molecule
		// update the current atoms with the newly added atoms
		val atomsByInfo = IdentityHashMap<ConfLib.AtomInfo,Atom>()
		for ((posAnchor, fragAnchor) in anchorMatch.pairs) {
			val res = posAnchor.findResidue(mol)
			for (atomInfo in frag.getAtomsFor(fragAnchor)) {
				val atom = Atom(atomInfo.element, atomInfo.name, Vector3d(conf.coords[atomInfo]))
				atomsByInfo[atomInfo] = atom
				mol.atoms.add(atom)
				res?.atoms?.add(atom)
				posAnchor.attachedAtoms.add(atom)
			}
		}

		// add the bonds
		for (bond in frag.bonds) {
			val atoma = atomsByInfo.getValue(bond.a)
			val atomb = atomsByInfo.getValue(bond.b)
			mol.bonds.add(atoma, atomb)
		}

		for ((posAnchor, fragAnchor) in anchorMatch.pairs) {

			// add the anchor bonds
			posAnchor.bondToAnchors(fragAnchor) { atomInfos, anchorAtom ->
				for (bondedInfo in atomInfos) {
					val atom = atomsByInfo.getValue(bondedInfo)
					mol.bonds.add(anchorAtom, atom)
				}
			}

			// align the conf coords
			posAnchor.align(conf.anchorCoords.getValue(fragAnchor))
		}
	}

	/**
	 * Looks at the current atoms and the bonding pattern to see which anchor group is currently in use.
	 */
	fun getCurrentAnchors() =
		anchorGroups.first { anchors ->
			anchors.all { anchor -> anchor.matchesBonds(mol) }
		}

	/**
	 * Makes a fragment from the existing atoms and coords.
	 */
	fun makeFragment(fragId: String, fragName: String, confId: String = fragId, confName: String = fragName): ConfLib.Fragment {

		val posAnchors = getCurrentAnchors()

		// make the atom infos
		val currentAtoms = posAnchors.flatMap { it.attachedAtoms }
		val atomInfos = Atom.identityMap<ConfLib.AtomInfo>().apply {
			for ((i, atom) in currentAtoms.withIndex()) {
				put(atom, ConfLib.AtomInfo(i + 1, atom.name, atom.element))
			}
		}

		fun Atom.info() =
			atomInfos[this]
				?: throw NoSuchElementException("no atom info for $name")

		// translate the anchors
		var nextAnchorId = 1
		val anchorsFragByPos = posAnchors.associateWith { posAnchor ->
			posAnchor.translate(nextAnchorId++, mol) { atom -> atom.info() }
		}

		return ConfLib.Fragment(
			id = fragId,
			name = fragName,
			atoms = atomInfos.values
				.sortedBy { it.id }
				.toList(),
			bonds = currentAtoms
				.flatMap { atom ->
					val atomInfo = atom.info()
					mol.bonds.bondedAtomsSorted(atom)
						.filter { it in currentAtoms }
						.mapNotNull { bondedAtom ->
							val bondedInfo = bondedAtom.info()
							// keep only bonds in one direction
							if (atomInfo.id < bondedInfo.id) {
								ConfLib.Bond(atomInfo, bondedInfo)
							} else {
								null
							}
						}
				},
			anchors = anchorsFragByPos.values
				.sortedBy { it.id },
			confs = mapOf(
				confId to ConfLib.Conf(
					id = confId,
					name = confName,
					description = null,
					coords = currentAtoms
						.associate { atom ->
							atom.info() to Vector3d(atom.pos)
						},
					anchorCoords = anchorsFragByPos
						.map { (posAnchor, fragAnchor) ->
							fragAnchor to posAnchor.translateCoords()
						}
						.associate { it }
				)
			)
		)
	}

	sealed class Anchor {

		abstract val anchorAtoms: List<Atom>

		/**
		 * The atoms that will be replaced by the next mutation, or re-located by the next conformation.
		 * These are not necessarily the atoms from the original molecule,
		 * but could be the atoms that resulted from setting a conformation.
		 */
		abstract val attachedAtoms: MutableSet<Atom>

		abstract fun isAnchorCompatible(anchor: ConfLib.Anchor): Boolean
		abstract fun bondToAnchors(anchor: ConfLib.Anchor, bondFunc: (List<ConfLib.AtomInfo>, Atom) -> Unit)

		/**
		 * Transforms the attached atoms from the ConfLib coordinate system
		 * into the molecular coordinate system.
		 */
		abstract fun align(anchorCoords: ConfLib.AnchorCoords)

		/**
		 * Returns true iff the bond pattern in the attached atoms
		 * matches the expected pattern for this anchor.
		 */
		abstract fun matchesBonds(mol: Molecule): Boolean

		abstract fun translate(id: Int, mol: Molecule, getAtomInfo: (Atom) -> ConfLib.AtomInfo): ConfLib.Anchor
		abstract fun translateCoords(): ConfLib.AnchorCoords

		/**
		 * Returns the residue of the first anchor atom, if any
		 */
		fun findResidue(mol: Molecule): Polymer.Residue? =
			(mol as? Polymer)?.findResidue(anchorAtoms.first())

		// a few convenience functions for transforing a list of atoms
		protected fun Iterable<Atom>.translate(t: Vector3d) {
			for (atom in this) {
				atom.pos.add(t)
			}
		}
		protected fun Iterable<Atom>.rotate(q: Quaterniond) {
			for (atom in this) {
				atom.pos.rotate(q)
			}
		}

		protected fun Molecule.getConnectedAtoms(anchorAtom: Atom): List<Atom> =
			bonds.bondedAtoms(anchorAtom)
				.filter { it in attachedAtoms }
				.flatMap { source ->
					bfs(
						source,
						visitSource = true,
						shouldVisit = { _, dst, _ -> dst !== anchorAtom }
					)
					.map { it.atom }
				}

		data class Single(
			override val attachedAtoms: MutableSet<Atom>,
			val a: Atom,
			val b: Atom,
			val c: Atom
		) : Anchor() {

			override val anchorAtoms = listOf(a, b, c)

			override fun isAnchorCompatible(anchor: ConfLib.Anchor) = anchor is ConfLib.Anchor.Single

			fun ConfLib.Anchor.cast() = this as ConfLib.Anchor.Single
			fun ConfLib.AnchorCoords.cast() = this as ConfLib.AnchorCoords.Single

			override fun bondToAnchors(anchor: ConfLib.Anchor, bondFunc: (List<ConfLib.AtomInfo>, Atom) -> Unit) {
				bondFunc(anchor.cast().bonds, a)
			}

			/**
			 * Aligns the attached atoms such that:
			 *   anchors a coincide
			 *   anchor vectors a-b are parallel and in the same direction
			 *   anchor planes a,b,c are parallel
			 * This alignment method exactly aligns the two coordinate systems without approximation.
			 */
			override fun align(anchorCoords: ConfLib.AnchorCoords) {

				val coords = anchorCoords.cast()

				// center coords on a
				attachedAtoms.translate(
					Vector3d(coords.a)
						.negate()
				)

				// rotate so anchor a->b vectors are parallel, and the a,b,c planes are parallel
				attachedAtoms.rotate(
					Quaterniond()
						.lookAlong(
							Vector3d(coords.b).sub(coords.a),
							Vector3d(coords.c).sub(coords.a)
						)
						.premul(
							Quaterniond()
								.lookAlong(
									Vector3d(b.pos).sub(a.pos),
									Vector3d(c.pos).sub(a.pos)
								)
								.invert()
						)
				)

				// translate back to a
				attachedAtoms.translate(a.pos)
			}

			override fun matchesBonds(mol: Molecule): Boolean {

				// does this exactly match the attached atoms?
				return attachedAtoms == mol.getConnectedAtoms(a).toIdentitySet()
			}

			override fun translate(id: Int, mol: Molecule, getAtomInfo: (Atom) -> ConfLib.AtomInfo) =
				ConfLib.Anchor.Single(
					id,
					bonds = mol.bonds.bondedAtoms(a)
						.filter { it in attachedAtoms }
						.map { getAtomInfo(it) }
				)

			override fun translateCoords() =
				ConfLib.AnchorCoords.Single(
					Vector3d(a.pos),
					Vector3d(b.pos),
					Vector3d(c.pos)
				)
		}

		data class Double(
			override val attachedAtoms: MutableSet<Atom>,
			val a: Atom,
			val b: Atom,
			val c: Atom,
			val d: Atom
		) : Anchor() {

			override val anchorAtoms = listOf(a, b, c, d)

			override fun isAnchorCompatible(anchor: ConfLib.Anchor) = anchor is ConfLib.Anchor.Double

			fun ConfLib.Anchor.cast() = this as ConfLib.Anchor.Double
			fun ConfLib.AnchorCoords.cast() = this as ConfLib.AnchorCoords.Double

			override fun bondToAnchors(anchor: ConfLib.Anchor, bondFunc: (List<ConfLib.AtomInfo>, Atom) -> Unit) {
				bondFunc(anchor.cast().bondsa, a)
				bondFunc(anchor.cast().bondsb, b)
			}

			/**
			 * Aligns the attached atoms such that:
			 *   anchors line segments a->b are in the same direction and have midpoints coincident at m.
			 *   anchor wedges c<-m->d are rotated about the a-b axis so their center directions are parallel.
			 * Since the different anchors can have different lengths of the a-b line segments,
			 * and different dihedral angles c->a->b->d, this method is an approximate method for
			 * aligning the two coordinate systems that tries to keep the error from accumulating
			 * all in one linear or angular distance.
			 */
			override fun align(anchorCoords: ConfLib.AnchorCoords) {

				val coords = anchorCoords.cast()

				// let m = midpoint between a and b
				val coordsm = Vector3d(coords.a)
					.add(coords.b)
					.mul(0.5)
				val mpos = Vector3d(a.pos)
					.add(b.pos)
					.mul(0.5)

				// center coords on m
				attachedAtoms.translate(
					Vector3d(coordsm)
						.negate()
				)

				// rotate into a coordinate system where:
				//   a->b becomes +z,
				//   a->c lies in the x,z plane
				val coordsQ = Quaterniond()
					.lookAlong(
						Vector3d(coords.b).sub(coords.a),
						Vector3d(coords.c).sub(coords.a)
					)
				val posQ = Quaterniond()
					.lookAlong(
						Vector3d(b.pos).sub(a.pos),
						Vector3d(c.pos).sub(a.pos)
					)
				attachedAtoms.rotate(coordsQ)

				// measure the c->a->b->d dihedral angles in [0,2pi)
				val coordsDihedralRadians = Vector3d(coords.d)
					.sub(coordsm)
					.rotate(coordsQ)
					.let { d -> atan2(d.y, d.x) }
					.normalizeZeroToTwoPI()
				val posDihedralRadians = abs(
					Vector3d(d.pos)
						.sub(mpos)
						.rotate(posQ)
						.let { d -> atan2(d.y, d.x) } -
					Vector3d(c.pos)
						.sub(mpos)
						.rotate(posQ)
						.let { c -> atan2(c.y, c.x) }
				).normalizeZeroToTwoPI()

				// rotate about +z half the difference in dihedral angles
				attachedAtoms.rotate(
					Quaterniond()
						.rotationZ((posDihedralRadians - coordsDihedralRadians)/2.0)
				)

				// rotate back to the molecular frame where:
				//   +z becomes a->b
				//   +x lies in the a,b,c plane
				attachedAtoms.rotate(
					Quaterniond(posQ)
						.invert()
				)

				// translate back to m
				attachedAtoms.translate(mpos)
			}

			override fun matchesBonds(mol: Molecule): Boolean {

				// do a and b have exactly the same connected atoms?
				val atomsa = mol.getConnectedAtoms(a).toIdentitySet()
				val atomsb = mol.getConnectedAtoms(b).toIdentitySet()
				if (atomsa != atomsb) {
					return false
				}

				// and do they exactly match the attached atoms?
				return attachedAtoms == atomsa
			}

			override fun translate(id: Int, mol: Molecule, getAtomInfo: (Atom) -> ConfLib.AtomInfo) =
				ConfLib.Anchor.Double(
					id,
					bondsa = mol.bonds.bondedAtoms(a)
						.filter { it in attachedAtoms }
						.map { getAtomInfo(it) },
					bondsb = mol.bonds.bondedAtoms(b)
						.filter { it in attachedAtoms }
						.map { getAtomInfo(it) }
				)

			override fun translateCoords() =
				ConfLib.AnchorCoords.Double(
					Vector3d(a.pos),
					Vector3d(b.pos),
					Vector3d(c.pos),
					Vector3d(d.pos)
				)
		}
	}
}
