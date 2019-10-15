package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.*
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
	 * The atoms that will be replaced or re-located by the next conformation.
	 * Needs to be initialized when creating a new design position.
	 * Automatically updated when setting a new conformation.
	 */
	val currentAtoms: MutableSet<Atom> = Atom.identitySet()

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
		for (atom in currentAtoms) {
			mol.atoms.remove(atom)
			if (mol is Polymer) {
				mol.findResidue(atom)?.atoms?.remove(atom)
			}
		}
		currentAtoms.clear()

		// copy the atoms from the conf and add them to the molecule
		// update the current atoms with the newly added atoms
		val atomsByInfo = IdentityHashMap<ConfLib.AtomInfo,Atom>()
		for ((posAnchor, fragAnchor) in anchorMatch.pairs) {
			val res = posAnchor.findResidue()
			for (atomInfo in frag.getAtomsFor(fragAnchor)) {

				// make the atom
				val atom = Atom(atomInfo.element, atomInfo.name, Vector3d(conf.coords[atomInfo]))
				atomsByInfo[atomInfo] = atom

				// add it to the mol
				mol.atoms.add(atom)
				res?.atoms?.add(atom)

				// update the current atoms
				currentAtoms.add(atom)
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
	fun getCurrentAnchorGroups() =
		anchorGroups
			.filter { anchors ->

				// get the connected atoms for each anchor
				val atoms = anchors.map { it.getConnectedAtoms() }

				for (i in 0 until atoms.size) {

					// connected atoms should be a single connected component
					if (!anchors[i].connectedAtomsIsSingleComponent()) {
						return@filter false
					}

					// no two pairs of connected atoms should overlap
					for (j in 0 until i) {
						if (atoms[i].intersection(atoms[j]).isNotEmpty()) {
							return@filter false
						}
					}
				}

				// all the atoms should exactly cover the current atoms
				return@filter atoms.union() == currentAtoms
			}

	class IllegalAnchorsException(msg: String, val pos: DesignPosition) : IllegalStateException(msg)

	/**
	 * Makes a fragment from the existing atoms and coords.
	 */
	fun makeFragment(fragId: String, fragName: String, confId: String = fragId, confName: String = fragName): ConfLib.Fragment {

		val posAnchors = getCurrentAnchorGroups()
			.let { groups ->
				when {
					groups.isEmpty() -> throw IllegalAnchorsException("can't make fragment for this design position, no active anchors", this)
					groups.size > 1 -> throw IllegalAnchorsException("can't make fragment for this design position, multiple active anchors", this)
					else -> groups[0]
				}
			}

		// make the atom infos
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
			posAnchor.translate(nextAnchorId++) { atom -> atom.info() }
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

	abstract inner class Anchor {

		abstract val anchorAtoms: List<Atom>

		abstract fun getConnectedAtoms(): MutableSet<Atom>
		abstract fun connectedAtomsIsSingleComponent(): Boolean

		abstract fun isAnchorCompatible(anchor: ConfLib.Anchor): Boolean
		abstract fun bondToAnchors(anchor: ConfLib.Anchor, bondFunc: (List<ConfLib.AtomInfo>, Atom) -> Unit)

		/**
		 * Transforms the attached atoms from the ConfLib coordinate system
		 * into the molecular coordinate system.
		 */
		abstract fun align(anchorCoords: ConfLib.AnchorCoords)

		abstract fun translate(id: Int, getAtomInfo: (Atom) -> ConfLib.AtomInfo): ConfLib.Anchor
		abstract fun translateCoords(): ConfLib.AnchorCoords

		/**
		 * Returns the residue of the first anchor atom, if any
		 */
		fun findResidue(): Polymer.Residue? =
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
	}

	private fun Atom.getConnectedAtoms(included: Set<Atom>, blockers: Set<Atom> = emptySet()) =
		mol.bonds.bondedAtoms(this)
			.filter { it in included }
			.flatMap { sourceNeighbor ->
				mol.bfs(
					sourceNeighbor,
					visitSource = true,
					shouldVisit = { _, dst, _ -> dst in included && dst !in blockers }
				)
					.map { it.atom }
			}
			.toIdentitySet()

	inner class SingleAnchor(
		val a: Atom,
		val b: Atom,
		val c: Atom
	) : Anchor() {

		fun copy(a: Atom = this.a, b: Atom = this.b, c: Atom = this.c) =
			SingleAnchor(a, b, c)

		override val anchorAtoms = listOf(a, b, c)

		override fun getConnectedAtoms() =
			a.getConnectedAtoms(included = currentAtoms)

		override fun connectedAtomsIsSingleComponent() = true // this is trivially true, since there's only one anchor

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

			val connectedAtoms = getConnectedAtoms()
			val coords = anchorCoords.cast()

			// center coords on a
			connectedAtoms.translate(
				Vector3d(coords.a)
					.negate()
			)

			// rotate so anchor a->b vectors are parallel, and the a,b,c planes are parallel
			connectedAtoms.rotate(
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
							.conjugate()
					)
			)

			// translate back to a
			connectedAtoms.translate(a.pos)
		}

		override fun translate(id: Int, getAtomInfo: (Atom) -> ConfLib.AtomInfo) =
			ConfLib.Anchor.Single(
				id,
				bonds = mol.bonds.bondedAtoms(a)
					.filter { it in currentAtoms }
					.map { getAtomInfo(it) }
			)

		override fun translateCoords() =
			ConfLib.AnchorCoords.Single(
				Vector3d(a.pos),
				Vector3d(b.pos),
				Vector3d(c.pos)
			)
	}

	inner class DoubleAnchor(
		val a: Atom,
		val b: Atom,
		val c: Atom,
		val d: Atom
	) : Anchor() {

		fun copy(a: Atom = this.a, b: Atom = this.b, c: Atom = this.c, d: Atom = this.d) =
			DoubleAnchor(a, b, c, d)

		override val anchorAtoms = listOf(a, b, c, d)

		override fun getConnectedAtoms() =
			listOf(
				a.getConnectedAtoms(included = currentAtoms),
				b.getConnectedAtoms(included = currentAtoms)
			).union()

		override fun connectedAtomsIsSingleComponent() =
			listOf(
				a.getConnectedAtoms(included = currentAtoms),
				b.getConnectedAtoms(included = currentAtoms)
			).intersection().isNotEmpty()

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

			val connectedAtoms = getConnectedAtoms()
			val coords = anchorCoords.cast()

			// let m = midpoint between a and b
			val coordsm = Vector3d(coords.a)
				.add(coords.b)
				.mul(0.5)
			val mpos = Vector3d(a.pos)
				.add(b.pos)
				.mul(0.5)

			/* DEBUG: show linear error
			val coordsALen = Vector3d(coords.a).sub(coordsm).length()
			val posALen = Vector3d(a.pos).sub(mpos).length()
			println("delta distance: ${abs(coordsALen - posALen)}")
			*/

			// center coords on m
			connectedAtoms.translate(
				Vector3d(coordsm)
					.negate()
			)

			// rotate into a coordinate system where:
			//   a->b becomes +z,
			//   a->c lies in the y,z plane
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
			connectedAtoms.rotate(coordsQ)

			// measure the c->a->b->d dihedral angles in [0,2pi)
			val coordsDihedralRadians = abs(
				Vector3d(coords.d)
					.sub(coordsm)
					.rotate(coordsQ)
					.let { d -> atan2(d.y, d.x) } -
				Vector3d(coords.c)
					.sub(coordsm)
					.rotate(coordsQ)
					.let { c -> atan2(c.y, c.x) }
			).normalizeZeroToTwoPI()
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

			/* DEBUG: show angular error
			println("delta angle: ${abs(coordsDihedralRadians - posDihedralRadians).toDegrees()/2.0}")
			*/

			// rotate about +z half the difference in dihedral angles
			connectedAtoms.rotate(
				Quaterniond()
					.rotationZ((posDihedralRadians - coordsDihedralRadians)/2.0)
			)

			// rotate back to the molecular frame where:
			//   +z becomes a->b
			//   +y lies in the a,b,c plane
			connectedAtoms.rotate(
				Quaterniond(posQ)
					.conjugate()
			)

			// translate back to m
			connectedAtoms.translate(mpos)
		}

		override fun translate(id: Int, getAtomInfo: (Atom) -> ConfLib.AtomInfo) =
			ConfLib.Anchor.Double(
				id,
				bondsa = mol.bonds.bondedAtoms(a)
					.filter { it in currentAtoms }
					.map { getAtomInfo(it) },
				bondsb = mol.bonds.bondedAtoms(b)
					.filter { it in currentAtoms }
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
