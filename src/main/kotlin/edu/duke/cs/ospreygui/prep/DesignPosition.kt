package edu.duke.cs.ospreygui.prep

import cuchaz.kludge.tools.isFinite
import cuchaz.kludge.tools.toString
import edu.duke.cs.molscope.molecule.*
import edu.duke.cs.molscope.tools.normalizeZeroToTwoPI
import edu.duke.cs.ospreygui.io.ConfLib
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.*
import kotlin.NoSuchElementException
import kotlin.math.abs
import kotlin.math.atan2


/**
 * A position in a molecule that allows removing the existing atoms and
 * aligning and attaching other conformations via an anchor atom system.
 */
class DesignPosition(
	var name: String,
	var type: String,
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

	fun compatibleFragments(conflib: ConfLib) =
		conflib.fragments
			.values
			.filter { isFragmentCompatible(it) }

	data class AnchorMatch(
		val posAnchors: List<Anchor>,
		val fragAnchors: List<ConfLib.Anchor>
	) {
		val pairs = posAnchors.zip(fragAnchors)

		fun findPosAnchor(fragAnchor: ConfLib.Anchor) =
			pairs
				.find { it.second === fragAnchor }
				?.first
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

	abstract inner class AtomResolver {

		inner class NoAtomException(val p: ConfLib.AtomPointer)
			: RuntimeException("can't find atom from pointer: $p at design position $name")

		abstract fun resolve(p: ConfLib.AtomPointer): Atom?

		fun resolveOrThrow(p: ConfLib.AtomPointer) =
			resolve(p) ?: throw NoAtomException(p)
	}
	var atomResolver: AtomResolver? = null
	val atomResolverOrThrow get() =
		atomResolver ?: throw NoSuchElementException("design position currently has no atom resolver")

	/**
	 * Removes the current atoms from the molecule, including associated bonds.
	 * Then adds the mutated atoms to the molecule, adding bonds and aligning to the anchor as necessary.
	 *
	 * If the molecule is a polymer, the new atoms are added to the residues of their anchor atoms.
	 *
	 * Also set the atom pointer resolver.
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

		try {
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
		} catch (ex: IllegalAlignmentException) {

			// add extra information to the exception before passing it along
			throw IllegalConformationException(this, frag, conf, "Can't set conformation, bad alignment", ex)
		}

		// set the atom pointer resolver
		atomResolver = object : AtomResolver() {
			override fun resolve(p: ConfLib.AtomPointer) =
				when (p) {
					is ConfLib.AtomInfo -> {
						atomsByInfo[p]
					}
					is ConfLib.AnchorAtomPointer -> {
						anchorMatch
							.findPosAnchor(p.anchor)
							?.anchorAtoms
							?.get(p.index)
					}
					else -> throw IllegalArgumentException("unrecognized atom pointer type: ${p::class.simpleName}")
				}
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
	fun makeFragment(
		fragId: String,
		fragName: String,
		confId: String = fragId,
		confName: String = fragName,
		dofs: List<ConfLib.DegreeOfFreedom> = emptyList()
	): ConfLib.Fragment {

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

		val atoms = atomInfos.values
			.sortedBy { it.id }
			.toList()
		val anchors = anchorsFragByPos.values
			.sortedBy { it.id }

		return ConfLib.Fragment(
			id = fragId,
			name = fragName,
			type = type,
			atoms = atoms,
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
			anchors = anchors,
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
			),
			dofs = dofs
				.map { it.copyTo(atoms, anchors, it.id) }
		)
	}

	sealed class Anchor(
		val pos: DesignPosition
	) {

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
			(pos.mol as? Polymer)?.findResidue(anchorAtoms.first())

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

		protected fun Atom.getConnectedAtoms(included: Set<Atom>, blockers: Set<Atom> = emptySet()) =
			this@Anchor.pos.mol.bonds.bondedAtoms(this)
				.filter { it in included }
				.flatMap { sourceNeighbor ->
					this@Anchor.pos.mol
						.bfs(
							sourceNeighbor,
							visitSource = true,
							shouldVisit = { _, dst, _ -> dst in included && dst !in blockers }
						)
						.map { it.atom }
				}
				.toIdentitySet()

		class Single(
			pos: DesignPosition,
			val a: Atom,
			val b: Atom,
			val c: Atom
		) : Anchor(pos) {

			fun copy(a: Atom = this.a, b: Atom = this.b, c: Atom = this.c) =
				Single(pos, a, b, c)

			override val anchorAtoms = listOf(a, b, c)

			override fun getConnectedAtoms() =
				a.getConnectedAtoms(included = pos.currentAtoms)

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
						.apply {
							if (!isFinite()) {
								throw IllegalAlignmentException("conformation anchors a,b,c must not be co-linear, or nearly co-linear:\n\t" +
									listOf("a" to coords.a, "b" to coords.b, "c" to coords.c)
										.joinToString("\n\t") { (name, pos) -> "$name = ${pos.toString(2)}" }
								)
							}
						}
						.premul(
							Quaterniond()
								.lookAlong(
									Vector3d(b.pos).sub(a.pos),
									Vector3d(c.pos).sub(a.pos)
								)
								.apply {
									if (!isFinite()) {
										throw IllegalAlignmentException("design position anchor atoms a,b,c must not be co-linear, or nearly co-linear:\n\t" +
											listOf("a" to a, "b" to b, "c" to c)
												.joinToString("\n\t") { (name, atom) -> "$name = ${atom.name} ${atom.pos.toString(2)}" }
										)
									}
								}
								.conjugate()
						)
				)

				// translate back to a
				connectedAtoms.translate(a.pos)
			}

			override fun translate(id: Int, getAtomInfo: (Atom) -> ConfLib.AtomInfo) =
				ConfLib.Anchor.Single(
					id,
					bonds = pos.mol.bonds.bondedAtoms(a)
						.filter { it in pos.currentAtoms }
						.map { getAtomInfo(it) }
				)

			override fun translateCoords() =
				ConfLib.AnchorCoords.Single(
					Vector3d(a.pos),
					Vector3d(b.pos),
					Vector3d(c.pos)
				)
		}

		class Double(
			pos: DesignPosition,
			val a: Atom,
			val b: Atom,
			val c: Atom,
			val d: Atom
		) : Anchor(pos) {

			fun copy(a: Atom = this.a, b: Atom = this.b, c: Atom = this.c, d: Atom = this.d) =
				Double(pos, a, b, c, d)

			override val anchorAtoms = listOf(a, b, c, d)

			override fun getConnectedAtoms() =
				listOf(
					a.getConnectedAtoms(included = pos.currentAtoms),
					b.getConnectedAtoms(included = pos.currentAtoms)
				).union()

			override fun connectedAtomsIsSingleComponent() =
				listOf(
					a.getConnectedAtoms(included = pos.currentAtoms),
					b.getConnectedAtoms(included = pos.currentAtoms)
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
					.apply {
						if (!isFinite()) {
							throw IllegalAlignmentException("conformation anchors a,b,c must not be co-linear, or nearly co-linear:\n\t" +
								listOf("a" to coords.a, "b" to coords.b, "c" to coords.c)
									.joinToString("\n\t") { (name, pos) -> "$name = ${pos.toString(2)}" }
							)
						}
					}
				val posQ = Quaterniond()
					.lookAlong(
						Vector3d(b.pos).sub(a.pos),
						Vector3d(c.pos).sub(a.pos)
					)
					.apply {
						if (!isFinite()) {
							throw IllegalAlignmentException("design position anchor atoms a,b,c must not be co-linear, or nearly co-linear:\n\t" +
								listOf("a" to a, "b" to b, "c" to c)
									.joinToString("\n\t") { (name, atom) -> "$name = ${atom.name} ${atom.pos.toString(2)}" }
							)
						}
					}
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
					bondsa = pos.mol.bonds.bondedAtoms(a)
						.filter { it in pos.currentAtoms }
						.map { getAtomInfo(it) },
					bondsb = pos.mol.bonds.bondedAtoms(b)
						.filter { it in pos.currentAtoms }
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

	// convience shortcuts to emulate inner classes
	fun anchorSingle(a: Atom, b: Atom, c: Atom) = Anchor.Single(this, a, b, c)
	fun anchorDouble(a: Atom, b: Atom, c: Atom, d: Atom) = Anchor.Double(this, a, b, c, d)

	class IllegalAlignmentException(msg: String)
		: IllegalArgumentException("Can't align conformation to anchor:\n$msg")

	class IllegalConformationException(
		val pos: DesignPosition,
		val frag: ConfLib.Fragment,
		val conf: ConfLib.Conf,
		msg: String,
		cause: Throwable? = null
	) : IllegalArgumentException("Design position ${pos.name} can't set conformation:\n\tfragment = ${frag.id}\n\tconformation = ${conf.id}\n$msg", cause)
}
