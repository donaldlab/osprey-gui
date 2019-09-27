package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import org.tomlj.Toml
import org.tomlj.TomlPosition


/**
 * Save the molecules to the OMOL (OSPREY molecule) format.
 */
fun List<Molecule>.toOMOL(): String {

	val buf = StringBuilder()
	fun write(str: String, vararg args: Any) = buf.append(String.format(str, *args))

	forEachIndexed { moli, mol ->

		if (moli > 0) {
			write("\n")
		}

		write("[molecule.$moli]\n")

		// write the name
		write("name = %s\n", mol.name.quote())

		// write the type, if any
		mol.type?.let { type ->
			write("type = %s\n", type.quote())
		}

		write("\n")

		// write the atoms
		val indicesByAtom = HashMap<Atom,Int>()
		write("atoms = [\n")
		var atomi = 0
		for (atom in mol.atoms) {
			write("\t{ i=%5d, name=%7s, x=%12.6f, y=%12.6f, z=%12.6f, elem=%3s },\n",
				atomi,
				atom.name.quote(),
				atom.pos.x, atom.pos.y, atom.pos.z,
				atom.element.symbol.quote()
			)
			indicesByAtom[atom] = atomi
			atomi += 1
		}
		write("]\n")

		write("\n")

		// write the bonds
		write("bonds = [\n")
		for ((a1, a2) in mol.bonds.toSet()) {
			val i1 = indicesByAtom[a1]!!
			val i2 = indicesByAtom[a2]!!
			write("\t[%5d,%5d], # %6s - %-6s\n",
				i1, i2,
				a1.name, a2.name
			)
		}
		write("]\n")

		// write the polymer, if needed
		if (mol is Polymer) {

			write("\n")
			write("[molecule.$moli.polymer]\n")

			for (chain in mol.chains) {
				write("%s = [\n", chain.id.quote())
				for (res in chain.residues) {
					write("\t{ id=%7s, type=%6s, atoms=[%s] },\n",
						res.id.quote(),
						res.type.quote(),
						res.atoms
							.map { atom -> indicesByAtom[atom] }
							.joinToString(", ") { it.toString() }
					)
				}
				write("]\n")
			}
		}
	}

	return buf.toString()
}

fun Molecule.toOMOL() = listOf(this).toOMOL()

private fun String.quote() =
	replace("\\", "\\\\")
		.replace("\"", "\\\"")
		.let { str -> "\"$str\"" }


/**
 * Read molecules from the OMOL (OSPREY molecule) format.
 */
fun Molecule.Companion.fromOMOL(toml: String, throwOnMissingAtoms: Boolean = true): List<Molecule> {

	// parse the TOML
	val doc = Toml.parse(toml)
	if (doc.hasErrors()) {
		throw TomlParseException("TOML parsing failure:\n${doc.errors().joinToString("\n")}")
	}

	val molsTable = doc.getTable("molecule") ?: throw TomlParseException("molecule table not found")
	val molIndices = molsTable.keySet()
		.map { it.toIntOrNull() ?: throw TomlParseException("molecule index $it is not a number") }

	val mols = ArrayList<Molecule>()

	for (moli in molIndices) {
		val molTable = molsTable.getTable("$moli")!!

		// read the name
		val name = molTable.getStringOrThrow("name")

		// read the type, if any
		val type = molTable.getString("type")

		// make the molecule instance
		val mol = if (molTable.contains("polymer")) {
			Polymer(name)
		} else {
			Molecule(name, type)
		}
		mols.add(mol)

		val atoms = HashMap<Int,Atom>()
		fun getAtom(i: Int, pos: TomlPosition? = null): Atom? =
			atoms[i] ?: if (throwOnMissingAtoms) {
				throw TomlParseException("atom not found at index $i", pos)
			} else {
				null
			}

		// read the atoms
		val atomsArray = molTable.getArrayOrThrow("atoms")
		if (!atomsArray.containsTables()) {
			throw TomlParseException("atoms does not contain tables", molTable.inputPositionOf("atoms"))
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
				throw TomlParseException("duplicate atom index: $i", pos)
			}
			atoms[index] = atom
		}

		// read the bonds
		val bondsArray = molTable.getArrayOrThrow("bonds")
		if (!bondsArray.containsArrays()) {
			throw TomlParseException("bonds does not contain arrays", molTable.inputPositionOf("bonds"))
		}
		for (i in 0 until bondsArray.size()) {
			val bondArray = bondsArray.getArray(i)
			val pos = bondsArray.inputPositionOf(i)

			if (!bondArray.containsLongs()) {
				throw TomlParseException("bond does not contain integers", pos)
			}

			val a1 = getAtom(bondArray.getLong(0).toInt()) ?: continue
			val a2 = getAtom(bondArray.getLong(1).toInt()) ?: continue
			mol.bonds.add(a1, a2)
		}

		// read the polymer, if needed
		molTable.getTable("polymer")?.let { polymerTable ->

			val polymer = mol as Polymer
			val pos = molTable.inputPositionOf("polymer")

			for (chainId in polymerTable.keySet()) {
				val chainArray = polymerTable.getArrayOrThrow(chainId, pos)
				if (!chainArray.containsTables()) {
					throw TomlParseException("chain does not contain tables", pos)
				}

				polymer.chains.add(Polymer.Chain(chainId).also { chain ->

					for (i in 0 until chainArray.size()) {
						val residueTable = chainArray.getTable(i)
						val resPos = chainArray.inputPositionOf(i)

						chain.residues.add(Polymer.Residue(
							residueTable.getStringOrThrow("id", resPos),
							residueTable.getStringOrThrow("type", resPos),
							atoms =
								residueTable.getArrayOrThrow("atoms", resPos).let { resAtomsArray ->
									if (!resAtomsArray.containsLongs()) {
										throw TomlParseException("field \"atoms\" doesn't contain integers", resPos)
									}
									(0 until resAtomsArray.size()).mapNotNull { getAtom(resAtomsArray.getLong(it).toInt()) }
								}
						))
					}
				})
			}
		}
	}

	return mols
}
