package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.Element
import org.joml.Vector3d
import org.tomlj.Toml
import org.tomlj.TomlPosition


/**
 * Conformation Library
 */
class ConfLib(
	val name: String,
	val fragments: Map<String,Fragment>,
	val description: String? = null,
	val citation: String? = null
) {

	data class AtomInfo(
		val id: Int,
		val name: String,
		val element: Element
	)

	data class AnchorInfo(
		val id: Int,
		val element: Element,
		val pos: Vector3d
	)

	data class Bond(
		val a: AtomInfo,
		val b: AtomInfo
	)

	data class AnchorBond(
		val atom: AtomInfo,
		val anchor: AnchorInfo
	)

	data class Conf(
		val id: String,
		val name: String,
		val description: String?,
		val coords: Map<AtomInfo,Vector3d>
	)

	data class Fragment(
		val id: String,
		val name: String,
		val atoms: List<AtomInfo>,
		val bonds: List<Bond>,
		val anchor: List<AnchorInfo>,
		val anchorBonds: List<AnchorBond>,
		val confs: Map<String,Conf>
	) {

		val anchor1 get() = anchor.getOrNull(0) ?: throw NoSuchElementException("anchor 1 is missing")
		val anchor2 get() = anchor.getOrNull(1) ?: throw NoSuchElementException("anchor 2 is missing")
		val anchor3 get() = anchor.getOrNull(2) ?: throw NoSuchElementException("anchor 3 is missing")
	}

	companion object {

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

				// read the anchor
				val anchor = HashMap<Int,AnchorInfo>()
				val anchorArray = fragTable.getArrayOrThrow("anchor", fragPos)
				if (anchorArray.size() < 3) {
					throw TomlParseException("There should be at least 3 anchor atoms in fragment $fragId", fragPos)
				}
				for (i in 0 until anchorArray.size()) {
					val atomTable = anchorArray.getTable(i)
					val pos = anchorArray.inputPositionOf(i)

					val id = atomTable.getIntOrThrow("id", pos)
					val xyzArray = atomTable.getArrayOrThrow("xyz", pos)
					anchor[id] = AnchorInfo(
						id,
						Element[atomTable.getStringOrThrow("elem", pos)],
						Vector3d(
							xyzArray.getDouble(0),
							xyzArray.getDouble(1),
							xyzArray.getDouble(2)
						)
					)
				}

				fun getAnchor(id: Int, pos: TomlPosition? = null) =
					anchor[id] ?: throw TomlParseException("no anchor with id $id in fragment $fragId", pos)

				// read the anchor bonds
				val anchorBonds = ArrayList<AnchorBond>()
				val interArray = fragTable.getArrayOrThrow("anchorBonds", fragPos)
				for (i in 0 until interArray.size()) {
					val bondArray = interArray.getArray(i)
					val pos = interArray.inputPositionOf(i)

					anchorBonds.add(AnchorBond(
						getAtom(bondArray.getInt(0), pos),
						getAnchor(bondArray.getInt(1), pos)
					))
				}

				// read the confs
				val confs = HashMap<String,Conf>()
				val confsTable = fragTable.getTableOrThrow("conf", fragPos)
				for (confId in confsTable.keySet()) {
					val confTable = confsTable.getTableOrThrow(confId)
					val confPos = confsTable.inputPositionOf(confId)

					val name = confTable.getString("name") ?: confId
					val desc = confTable.getString("description")
					val dihedralDegrees = confTable.getDouble("dihedralDegrees")
					val coords = HashMap<AtomInfo,Vector3d>()

					val coordsArray = confTable.getArrayOrThrow("coords", confPos)
					for (i in 0 until coordsArray.size()) {
						val coordTable = coordsArray.getTable(i)
						val pos = coordsArray.inputPositionOf(i)

						val atomInfo = getAtom(coordTable.getIntOrThrow("id", pos))
						val xyzArray = coordTable.getArrayOrThrow("xyz", pos)
						coords[atomInfo] = Vector3d(
							xyzArray.getDouble(0),
							xyzArray.getDouble(1),
							xyzArray.getDouble(2)
						)
					}

					confs[confId] = Conf(confId, name, desc, coords)
				}

				frags[fragId] = Fragment(
					fragId,
					fragName,
					atoms.map { (_, atom) -> atom },
					bonds,
					anchor.map { (_, atom) -> atom },
					anchorBonds,
					confs
				)
			}

			return ConfLib(libName, frags, libDesc, citation)
		}
	}
}