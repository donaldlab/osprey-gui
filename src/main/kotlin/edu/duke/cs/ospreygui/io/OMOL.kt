package edu.duke.cs.ospreygui.io

import cuchaz.kludge.tools.x
import cuchaz.kludge.tools.y
import cuchaz.kludge.tools.z
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import org.tomlj.Toml
import org.tomlj.TomlPosition
import org.tomlj.TomlTable


/**
 * Save the molecule to the OMOL (OSPREY molecule) format.
 */
fun Molecule.toOMOL(): String {

	val buf = StringBuilder()
	fun write(str: String, vararg args: Any) = buf.append(String.format(str, *args))

	// write the name
	write("name = %s\n", name.quote())

	write("\n")

	// write the atoms
	val indicesByAtom = HashMap<Atom,Int>()
	write("atoms = [\n")
	var i = 0
	for (atom in atoms) {
		write("\t{ i=%5d, name=%7s, x=%12.6f, y=%12.6f, z=%12.6f, elem=%3s },\n",
			i,
			atom.name.quote(),
			atom.pos.x, atom.pos.y, atom.pos.z,
			atom.element.symbol.quote()
		)
		indicesByAtom[atom] = i
		i += 1
	}
	write("]\n")

	write("\n")

	// write the bonds
	write("bonds = [\n")
	for (a1 in atoms) {
		val i1 = indicesByAtom[a1]!!
		for (a2 in bonds.bondedAtoms(a1)) {
			val i2 = indicesByAtom[a2] ?: throw IllegalArgumentException("molecule is bonded to atoms not in this molecule")
			if (i1 < i2) {
				write("\t[%5d,%5d], # %6s - %-6s\n",
					i1, i2,
					a1.name, a2.name
				)
			}
		}
	}
	write("]\n")

	// write the polymer, if needed
	if (this is Polymer) {

		write("\n")
		write("[polymer]\n")

		for (chain in chains) {
			write("%s = [\n", chain.id.quote())
			for (res in chain.residues) {
				write("\t{ id=%7s, type=%6s, mainchain=[%s], sidechains=[%s] },\n",
					res.id.quote(),
					res.type.quote(),
					res.mainchain
						.map { atom -> indicesByAtom[atom] }
						.joinToString(", ") { it.toString() },
					res.sidechains.joinToString(", ") { sidechain ->
						"[${sidechain
							.map { atom -> indicesByAtom[atom] }
							.joinToString(", ") { it.toString() }
						}]"
					}
				)
			}
			write("]\n")
		}
	}

	return buf.toString()
}

private fun String.quote() =
	replace("\\", "\\\\")
		.replace("\"", "\\\"")
		.let { str -> "\"$str\"" }


/**
 * Read a molecule from the OMOL (OSPREY molecule) format.
 */
fun Molecule.Companion.fromOMOL(toml: String): Molecule {

	// parse the TOML
	val doc = Toml.parse(toml)
	if (doc.hasErrors()) {
		throw ParseException("TOML parsing failure:\n${doc.errors().joinToString("\n")}")
	}

	// read the name
	val name = doc.getStringOrThrow("name")

	// make the molecule instance
	val mol = if (doc.contains("polymer")) {
		Polymer(name)
	} else {
		Molecule(name)
	}

	val atoms = HashMap<Int,Atom>()
	fun getAtom(i: Int, pos: TomlPosition? = null) =
		atoms[i] ?: throw ParseException("atom not found at index $i", pos)

	// read the atoms
	val atomsArray = doc.getArrayOrThrow("atoms")
	if (!atomsArray.containsTables()) {
		throw ParseException("atoms does not contain tables", doc.inputPositionOf("atoms"))
	}
	for (i in 0 until atomsArray.size()) {
		val atomTable = atomsArray.getTable(i)
		val pos = atomsArray.inputPositionOf(i)

		val atom = Atom(
			Element[atomTable.getStringOrThrow("elem", pos)],
			atomTable.getStringOrThrow("name", pos),
			atomTable.getDoubleOrThrow("x", pos),
			atomTable.getDoubleOrThrow("y", pos),
			atomTable.getDoubleOrThrow("z", pos)
		)

		mol.atoms.add(atom)

		val index = atomTable.getIntOrThrow("i", pos)
		if (index in atoms) {
			throw ParseException("duplicate atom index: $i", pos)
		}
		atoms[index] = atom
	}

	// read the bonds
	val bondsArray = doc.getArrayOrThrow("bonds")
	if (!bondsArray.containsArrays()) {
		throw ParseException("bonds does not contain arrays", doc.inputPositionOf("bonds"))
	}
	for (i in 0 until bondsArray.size()) {
		val bondArray = bondsArray.getArray(i)
		val pos = bondsArray.inputPositionOf(i)

		if (!bondArray.containsLongs()) {
			throw ParseException("bond does not contain integers", pos)
		}

		mol.bonds.add(
			getAtom(bondArray.getLong(0).toInt()),
			getAtom(bondArray.getLong(1).toInt())
		)
	}

	// read the polymer, if needed
	doc.getTable("polymer")?.let { polymerTable ->

		val polymer = mol as Polymer
		val pos = doc.inputPositionOf("polymer")

		for (chainId in polymerTable.keySet()) {
			val chainArray = polymerTable.getArrayOrThrow(chainId, pos)
			if (!chainArray.containsTables()) {
				throw ParseException("chain does not contain tables", pos)
			}

			polymer.chains.add(Polymer.Chain(chainId).also { chain ->

				for (i in 0 until chainArray.size()) {
					val residueTable = chainArray.getTable(i)
					val resPos = chainArray.inputPositionOf(i)

					chain.residues.add(Polymer.Residue(
						residueTable.getStringOrThrow("id", resPos),
						residueTable.getStringOrThrow("type", resPos),
						mainchain =
							residueTable.getArrayOrThrow("mainchain", resPos).let { mainchainArray ->
								if (!mainchainArray.containsLongs()) {
									throw ParseException("field \"mainchain\" doesn't contain integers", resPos)
								}
								(0 until mainchainArray.size()).map { getAtom(mainchainArray.getLong(it).toInt()) }
							},
						sidechains =
							residueTable.getArrayOrThrow("sidechains", resPos).let { sidechainsArray ->
								(0 until sidechainsArray.size()).map { i ->
									sidechainsArray.getArray(i).let { sidechainArray ->
										if (!sidechainArray.containsLongs()) {
											throw ParseException("field \"sidechains\" doesn't contain integers", sidechainsArray.inputPositionOf(i))
										}
										(0 until sidechainArray.size()).map { getAtom(sidechainArray.getLong(it).toInt()) }
									}
								}
							}
					))
				}
			})
		}
	}

	return mol
}

private fun TomlTable.getStringOrThrow(key: String, pos: TomlPosition? = null) =
	getString(key) ?: throw ParseException("missing field \"$key\", or it is not a string", pos)

private fun TomlTable.getArrayOrThrow(key: String, pos: TomlPosition? = null) =
	getArray(key) ?: throw ParseException("missing field \"$key\", or it is not an array", pos)

private fun TomlTable.getDoubleOrThrow(key: String, pos: TomlPosition? = null) =
	getDouble(key) ?: throw ParseException("missing field \"$key\", or it is not a floating-point number", pos)

private fun TomlTable.getIntOrThrow(key: String, pos: TomlPosition? = null) =
	getLong(key)?.toInt() ?: throw ParseException("missing field \"$key\", or it is not an integer", pos)

class ParseException(msg: String, val pos: TomlPosition? = null) : RuntimeException(
	msg + if (pos != null) " at $pos" else ""
)
