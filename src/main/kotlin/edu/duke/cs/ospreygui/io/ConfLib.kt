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

	data class IntraBond(
		val a: AtomInfo,
		val b: AtomInfo
	)

	data class InterBond(
		val atom: AtomInfo,
		val anchorIndex: Int
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
		val intraBonds: List<IntraBond>,
		val interBonds: List<InterBond>,
		val confs: Map<String,Conf>
	)

	companion object {

		fun from(toml: String): ConfLib {

			// parse the TOML
			val doc = Toml.parse(toml)
			if (doc.hasErrors()) {
				throw TomlParseException("TOML parsing failure:\n${doc.errors().joinToString("\n")}")
			}

			// TODO: add positions to exceptions

			// read the header
			val libName = doc.getStringOrThrow("name")
			val libDesc = doc.getString("desc")
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
						Element.get(atomTable.getStringOrThrow("elem", pos))
					)
				}

				fun getAtom(id: Int, pos: TomlPosition? = null) =
					atoms[id] ?: throw TomlParseException("no atom with id $id in fragment $fragId", pos)

				// read the intra bonds
				val intraBonds = ArrayList<IntraBond>()
				val intraArray = fragTable.getArrayOrThrow("intraBonds", fragPos)
				for (i in 0 until intraArray.size()) {
					val bondArray = intraArray.getArray(i)
					val pos = intraArray.inputPositionOf(i)

					intraBonds.add(IntraBond(
						getAtom(bondArray.getInt(0), pos),
						getAtom(bondArray.getInt(1), pos)
					))
				}

				// read the inter bonds
				val interBonds = ArrayList<InterBond>()
				val interArray = fragTable.getArrayOrThrow("interBonds", fragPos)
				for (i in 0 until interArray.size()) {
					val bondArray = interArray.getArray(i)
					val pos = interArray.inputPositionOf(i)

					interBonds.add(InterBond(
						getAtom(bondArray.getInt(0), pos),
						bondArray.getInt(1)
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
					intraBonds,
					interBonds,
					confs
				)
			}

			return ConfLib(libName, frags, libDesc, citation)
		}
	}
}