package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.tools.identityHashSet
import org.joml.Vector3d
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlPosition
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


/**
 * Conformation Library
 */
class ConfLib(
	val name: String,
	val fragments: Map<String,Fragment>,
	val description: String? = null,
	val citation: String? = null
) {

	/**
	 * A globally unique id for each library, assigned at runtime.
	 * This id is not intrinsic to the library itself, so should not be persisted anywhere.
	 * Its only purpose is to allow assigning globally unique ids to fragments, conformations, etc at runtime.
	 */
	val runtimeId = "library-${nextId++}"

	/**
	 * A globally unique id for each fragment, assigned at runtime.
	 * This id is not intrinsic to the library itself, so should not be persisted anywhere.
	 */
	fun fragRuntimeId(frag: Fragment) =
		"$runtimeId.${frag.id}"

	/**
	 * A globally unique id for each conformation, assigned at runtime.
	 * This id is not intrinsic to the library itself, so should not be persisted anywhere.
	 */
	fun confRuntimeId(frag: Fragment, conf: Conf) =
		"$runtimeId.${frag.id}.${conf.id}"

	data class AtomInfo(
		val id: Int,
		val name: String,
		val element: Element
	) : AtomPointer

	data class Bond(
		val a: AtomInfo,
		val b: AtomInfo
	)

	sealed class Anchor {

		abstract val id: Int

		/**
		 * Find all atoms bonded to this anchor.
		 */
		abstract fun findAtoms(frag: Fragment): List<AtomInfo>

		data class Single(
			override val id: Int,
			val bonds: List<AtomInfo>
		) : Anchor() {

			override fun findAtoms(frag: Fragment) =
				this.bonds
					.flatMap { findAtoms(frag, it) }
					.toCollection(identityHashSet())
					.toList()
		}

		data class Double(
			override val id: Int,
			val bondsa: List<AtomInfo>,
			val bondsb: List<AtomInfo>
		) : Anchor() {

			override fun findAtoms(frag: Fragment) =
				listOf(bondsa, bondsb)
					.flatMap { bonds ->
						bonds.flatMap { findAtoms(frag, it) }
					}
					.toCollection(identityHashSet())
					.toList()
		}

		companion object {

			fun findAtoms(frag: Fragment, source: AtomInfo): List<AtomInfo> {

				// do BFS in the bond graph
				val toVisit = ArrayDeque<AtomInfo>()
				val visitScheduled = identityHashSet<AtomInfo>()

				fun scheduleVisit(atom: AtomInfo) {
					toVisit.add(atom)
					visitScheduled.add(atom)
				}

				// start with the source
				scheduleVisit(source)

				val out = ArrayList<AtomInfo>()

				while (true) {

					// visit the next atom
					val atom = toVisit.pollFirst() ?: break
					out.add(atom)

					// schedule visits to neighbors
					for (bondedAtom in frag.bondedAtoms(atom)) {
						if (bondedAtom !in visitScheduled) {
							scheduleVisit(bondedAtom)
						}
					}
				}

				return out
			}
		}
	}

	sealed class AnchorCoords {

		data class Single(
			val a: Vector3d,
			val b: Vector3d,
			val c: Vector3d
		) : AnchorCoords()

		data class Double(
			val a: Vector3d,
			val b: Vector3d,
			val c: Vector3d,
			val d: Vector3d
		) : AnchorCoords()
	}

	data class Conf(
		val id: String,
		val name: String,
		val description: String?,
		val coords: Map<AtomInfo,Vector3d>,
		val anchorCoords: Map<Anchor,AnchorCoords>
	)

	interface AtomPointer

	data class AnchorAtomPointer(
		val anchor: Anchor,
		val index: Int
	) : AtomPointer

	sealed class DegreeOfFreedom {

		abstract val id: Int

		data class DihedralAngle(
			override val id: Int,
			val a: AtomPointer,
			val b: AtomPointer,
			val c: AtomPointer,
			val d: AtomPointer
		) : DegreeOfFreedom()

		// TODO: other DoFs?
	}

	data class Fragment(
		val id: String,
		val name: String,
		val type: String,
		val atoms: List<AtomInfo>,
		val bonds: List<Bond>,
		val anchors: List<Anchor>,
		val confs: Map<String,Conf>,
		val dofs: List<DegreeOfFreedom>
	) {

		fun bondedAtoms(atom: AtomInfo): List<AtomInfo> =
			bonds
				.mapNotNull { (a, b) ->
					when {
						atom === a -> b
						atom === b -> a
						else -> null
					}
				}

		private val atomsByAnchor = IdentityHashMap<Anchor,List<AtomInfo>>()

		fun getAtomsFor(anchor: Anchor) =
			atomsByAnchor.getOrPut(anchor) {
				anchor.findAtoms(this)
			}
	}

	companion object {

		private var nextId = 0

		fun from(toml: String): ConfLib {

			// parse the TOML
			val doc = Toml.parse(toml)
			if (doc.hasErrors()) {
				throw TomlParseException("TOML parsing failure:\n${doc.errors().joinToString("\n")}")
			}

			// read the header
			val libName = doc.getStringOrThrow("name")
			val libDesc = doc.getString("description")
			val citation = doc.getString("citation")

			// read the fragments
			val fragsTable = doc.getTableOrThrow("frag")
			val frags = HashMap<String,Fragment>()
			for (fragId in fragsTable.keySet()) {
				val fragTable = fragsTable.getTableOrThrow(fragId)
				val fragPos = fragsTable.inputPositionOf(fragId)

				val fragName = fragTable.getString("name") ?: fragId
				val fragType = fragTable.getStringOrThrow("type")

				// read the atoms
				val atoms = HashMap<Int,AtomInfo>()
				val atomsArray = fragTable.getArrayOrThrow("atoms", fragPos)
				for (i in 0 until atomsArray.size()) {
					val atomTable = atomsArray.getTable(i)
					val pos = atomsArray.inputPositionOf(i)

					val id = atomTable.getIntOrThrow("id", pos)
					atoms[id] = AtomInfo(
						id,
						atomTable.getStringOrThrow("name", pos),
						Element[atomTable.getStringOrThrow("elem", pos)]
					)
				}

				fun getAtom(id: Int, pos: TomlPosition? = null) =
					atoms[id] ?: throw TomlParseException("no atom with id $id in fragment $fragId", pos)

				// read the bonds
				val bonds = ArrayList<Bond>()
				val bondsArray = fragTable.getArrayOrThrow("bonds", fragPos)
				for (i in 0 until bondsArray.size()) {
					val bondArray = bondsArray.getArray(i)
					val pos = bondsArray.inputPositionOf(i)

					bonds.add(Bond(
						getAtom(bondArray.getInt(0), pos),
						getAtom(bondArray.getInt(1), pos)
					))
				}

				fun TomlArray.toBonds(pos: TomlPosition): List<AtomInfo> =
					(0 until size())
						.map { i -> getAtom(getInt(i), pos) }

				// read the anchors
				val anchors = HashMap<Int,Anchor>()
				val anchorArray = fragTable.getArrayOrThrow("anchors", fragPos)
				for (i in 0 until anchorArray.size()) {
					val anchorTable = anchorArray.getTable(i)
					val pos = anchorArray.inputPositionOf(i)

					val id = anchorTable.getIntOrThrow("id", pos)
					val type = anchorTable.getString("type")?.toLowerCase()
					anchors[id] = when (type) {
						"single" -> {
							Anchor.Single(
								id,
								anchorTable.getArrayOrThrow("bonds", pos).toBonds(pos)
							)
						}
						"double" -> {
							Anchor.Double(
								id,
								anchorTable.getArrayOrThrow("bondsa", pos).toBonds(pos),
								anchorTable.getArrayOrThrow("bondsb", pos).toBonds(pos)
							)
						}
						else -> throw TomlParseException("unrecognized anchor type: $type", pos)
					}
				}

				fun getAnchor(id: Int, pos: TomlPosition? = null) =
					anchors[id] ?: throw TomlParseException("no anchor with id $id in fragment $fragId", pos)

				fun TomlArray.toVector3d() =
					Vector3d(
						getDouble(0),
						getDouble(1),
						getDouble(2)
					)

				// read the confs
				val confs = HashMap<String,Conf>()
				val confsTable = fragTable.getTableOrThrow("conf", fragPos)
				for (confId in confsTable.keySet()) {
					val confTable = confsTable.getTableOrThrow(confId)
					val confPos = confsTable.inputPositionOf(confId)

					val name = confTable.getString("name") ?: confId
					val desc = confTable.getString("description")
					val coords = IdentityHashMap<AtomInfo,Vector3d>()
					val anchorCoords = IdentityHashMap<Anchor,AnchorCoords>()

					val coordsArray = confTable.getArrayOrThrow("coords", confPos)
					for (i in 0 until coordsArray.size()) {
						val coordTable = coordsArray.getTable(i)
						val pos = coordsArray.inputPositionOf(i)

						val atomInfo = getAtom(coordTable.getIntOrThrow("id", pos))
						coords[atomInfo] = coordTable.getArrayOrThrow("xyz", pos).toVector3d()
					}

					val anchorCoordsArray = confTable.getArrayOrThrow("anchorCoords", confPos)
					for (i in 0 until anchorCoordsArray.size()) {
						val coordTable = anchorCoordsArray.getTable(i)
						val pos = anchorCoordsArray.inputPositionOf(i)

						val anchor = getAnchor(coordTable.getIntOrThrow("id", pos))
						anchorCoords[anchor] = when (anchor) {
							is Anchor.Single -> AnchorCoords.Single(
								a = coordTable.getArrayOrThrow("a", pos).toVector3d(),
								b = coordTable.getArrayOrThrow("b", pos).toVector3d(),
								c = coordTable.getArrayOrThrow("c", pos).toVector3d()
							)
							is Anchor.Double -> AnchorCoords.Double(
								a = coordTable.getArrayOrThrow("a", pos).toVector3d(),
								b = coordTable.getArrayOrThrow("b", pos).toVector3d(),
								c = coordTable.getArrayOrThrow("c", pos).toVector3d(),
								d = coordTable.getArrayOrThrow("d", pos).toVector3d()
							)
						}
					}

					confs[confId] = Conf(confId, name, desc, coords, anchorCoords)
				}

				fun makeAtomPointer(key: Any?, pos: TomlPosition): AtomPointer =
					when (key) {
						is Long -> {
							// look up the atom info by index
							getAtom(key.toInt(), pos)
						}
						is TomlArray -> {
							// make an anchor atom pointer from the two indices
							if (key.size() == 2 && key.containsLongs()) {
								val anchorIndex = key.getInt(0)
								val atomIndex = key.getInt(1)
								AnchorAtomPointer(
									getAnchor(anchorIndex),
									atomIndex - 1
								)
							} else {
								throw TomlParseException("atom pointer doesn't look like an anchor atom pointer: $key", pos)
							}
						}
						else -> throw TomlParseException("unrecognized atom pointer: $key", pos)
					}

				// read the dofs
				val dofs = HashMap<Int,DegreeOfFreedom>()
				val dofsArray = fragTable.getArrayOrThrow("dofs", fragPos)
				for (i in 0 until dofsArray.size()) {
					val dofTable = dofsArray.getTable(i)
					val pos = dofsArray.inputPositionOf(i)

					val id = dofTable.getIntOrThrow("id", pos)
					val type = dofTable.getStringOrThrow("type", pos)

					when (type) {
						"dihedral" -> {
							dofs[id] = DegreeOfFreedom.DihedralAngle(
								id,
								makeAtomPointer(dofTable.get("a"), pos),
								makeAtomPointer(dofTable.get("b"), pos),
								makeAtomPointer(dofTable.get("c"), pos),
								makeAtomPointer(dofTable.get("d"), pos)
							)
						}
						else -> throw TomlParseException("unrecognized degree of freedom type: $type", pos)
					}
				}

				frags[fragId] = Fragment(
					fragId,
					fragName,
					fragType,
					atoms.map { (_, atom) -> atom },
					bonds,
					anchors.map { (_, anchor) -> anchor },
					confs,
					dofs.map { (_, dof) -> dof }
				)
			}

			return ConfLib(libName, frags, libDesc, citation)
		}
	}
}

val ConfLib?.runtimeId get() = "__dynamic__"

fun ConfLib?.fragRuntimeId(frag: ConfLib.Fragment) =
	"$runtimeId.${frag.id}"

fun ConfLib?.confRuntimeId(frag: ConfLib.Fragment, conf: ConfLib.Conf) =
	"$runtimeId.${frag.id}.${conf.id}"
