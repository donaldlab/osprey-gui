package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import org.joml.Vector3d


// this file handles IO with the Mol2 format:
// http://chemyang.ccnu.edu.cn/ccb/server/AIMMS/mol2.pdf


/**
 * Save the molecule to the Mol2 format.
 */
fun Molecule.toMol2(): String {

	val mol = this

	val buf = StringBuilder()
	fun write(str: String, vararg args: Any) = buf.append(String.format(str, *args))

	// get the chains (either from the polymer, or if a small molecule, make a dummy chain)
	val chains = (mol as? Polymer)?.chains
		?: listOf(Polymer.Chain("A").apply {
			residues.add(Polymer.Residue("1", mol.type ?: "MOL", mol.atoms))
		})

	// assign an id to each residue
	val resIds = HashMap<Polymer.Residue,Int>().apply {
		var i = 1
		for (chain in chains) {
			for (res in chain.residues) {
				put(res, i)
				i += 1
			}
		}
	}

	// get the residue for each atom
	val residuesByAtom = HashMap<Atom,Polymer.Residue>().apply {
		for (chain in chains) {
			for (res in chain.residues) {
				for (atom in res.atoms) {
					put(atom, res)
				}
			}
		}
	}

	// get all the atom indices
	val indicesByAtom = HashMap<Atom,Int>()
	mol.atoms.forEachIndexed { i, atom ->
		indicesByAtom[atom] = i + 1
	}

	// flatten the bonds to a list
	data class Bond(val i: Int, val i1: Int, val i2: Int)
	val bonds = ArrayList<Bond>().apply {
		var i = 1
		for (a1 in atoms) {
			val i1 = indicesByAtom[a1]!!
			for (a2 in bonds.bondedAtoms(a1)) {
				val i2 = indicesByAtom[a2] ?: throw IllegalArgumentException("molecule is bonded to atoms not in this molecule")
				if (i1 < i2) {
					add(Bond(i, i1, i2))
					i += 1
				}
			}
		}
	}

	// write the molecule section
	write("@<TRIPOS>MOLECULE\n")
	write(mol.name.replace("\n", " ") + "\n")
	write("  ${mol.atoms.size} ${bonds.size} ${chains.sumBy { it.residues.size }}\n")
	when (this) {
		is Polymer -> write("BIOPOLYMER\n")
		else -> write("SMALL\n")
	}
	write("NO_CHARGES\n")
	write("\n")
	write("\n")

	// write the atom section
	write("@<TRIPOS>ATOM\n")
	for (atom in atoms) {
		val res = residuesByAtom[atom]!!
		write("  %d %s %.6f %.6f %.6f %s %s %s %f\n".format(
			indicesByAtom[atom],
			atom.name,
			atom.pos.x, atom.pos.y, atom.pos.z,
			atom.element.symbol, // atom type, required by LEaP
			// TODO: is the element symbol good enough here?
			resIds[res],
			res.type,
			0.0 // charge, required by LEaP
		))
	}

	// write the bond section
	write("@<TRIPOS>BOND\n")
	for (bond in bonds) {
		write("  %d %d %d %d\n".format(
			bond.i,
			bond.i1,
			bond.i2,
			1 // bond type, required by LEaP
			// TODO: is single bond good enough here?
		))
	}

	// write the substructure section
	write("@<TRIPOS>SUBSTRUCTURE\n")
	for (chain in chains) {
		for (res in chain.residues) {
			write("  %d %s %d %s %d %s %s\n".format(
				resIds[res],
				res.id,
				indicesByAtom[res.atoms.first()],
				"RESIDUE",
				1, // dictionary type, 1 is protein
				// TODO: what dictionary type(s) to use in general?
				chain.id,
				res.type
			))
		}
	}

	return buf.toString()
}

/**
 * Read a molecule in MOL2 format.
 */
fun Molecule.Companion.fromMol2(mol2: String): Molecule {

	// parse a few sections from the mol2 file
	val lines = mol2.lines()
	val sections = HashMap<Section,List<String>>()
	var i = 0
	fun peek() = lines.getOrNull(i) ?: ""
	fun next() = peek().also { i += 1 }
	while (i < lines.size) {

		val line = next()
		if (line.startsWith("@")) {

			// is this a section we care about?
			val section = Section[
				line.trim()
					.substring(1)
					.toUpperCase()
					.replace("<", "")
					.replace(">", "_")
			] ?: continue

			// grab the lines for this section
			val sectionLines = ArrayList<String>()
			while (i < lines.size && !peek().startsWith("@")) {
				next()
					.takeUnless { it.isBlank() }
					?.let { sectionLines.add(it) }
			}
			sections[section] = sectionLines
		}
	}

	// build a molecule from the sections
	val sectionMol = sections[Section.TRIPOS_MOLECULE]
		?: throw NoSuchElementException("missing MOLECULE section")
	val molName = sectionMol[0].trim()

	val sectionSub = sections[Section.TRIPOS_SUBSTRUCTURE]
		?: throw NoSuchElementException("missing SUBSTRUCTURE section")

	// if there's more than one substructure, assume the molecule is a polymer
	val mol = if (sectionSub.size > 1) {
		Polymer(molName)
	} else {

		// try to get the mol type from the (single) substructure
		val parts = sectionSub[0].tokenize()

		Molecule(molName, parts[6])
	}

	// read the atoms
	val sectionAtoms = sections[Section.TRIPOS_ATOM]
		?: throw NoSuchElementException("missing ATOM section")
	val atomsById = HashMap<String,Atom>()
	val atomsBySub = HashMap<String,ArrayList<Atom>>()
	for (line in sectionAtoms) {
		val parts = line.tokenize()

		val id = parts[0]
		val name = parts[1]
		val pos = Vector3d(
			parts[2].toDouble(),
			parts[3].toDouble(),
			parts[4].toDouble()
		)
		val type = parts[5]
		val subId = parts.getOrNull(6)

		val element = Element[type.split(".").first()]

		val atom = Atom(element, name, pos)
		atomsById[id] = atom
		mol.atoms.add(atom)

		// track the substructure, if needed
		if (subId != null) {
			atomsBySub.computeIfAbsent(subId) { ArrayList() }.add(atom)
		}
	}

	// read the bonds
	val sectionBonds = sections[Section.TRIPOS_BOND]
		?: throw NoSuchElementException("missing BOND section")

	for (line in sectionBonds) {
		val parts = line.tokenize()

		val i1 = parts[1]
		val i2 = parts[2]

		val a1 = atomsById[i1] ?: throw NoSuchElementException("no atom with id $i1")
		val a2 = atomsById[i2] ?: throw NoSuchElementException("no atom with id $i2")

		mol.bonds.add(a1, a2)
	}

	// read the polmyer, if any
	if (mol is Polymer) {

		for (line in sectionSub) {
			val parts = line.tokenize()

			val id = parts[0]
			val name = parts[1]
			val chainId = parts.getOrNull(5) ?: "A"
			val subType = parts.getOrNull(6) ?: "MOL"

			val chain = mol.chains
				.find { it.id == chainId }
				?: Polymer.Chain(chainId).apply {
					mol.chains.add(this)
				}

			val atoms = atomsBySub[id] ?: ArrayList()

			chain.residues.add(Polymer.Residue(name, subType, atoms))
		}
	}

	return mol
}


private fun String.tokenize() = split(" ").filter { it.isNotBlank() }


private enum class Section {

	TRIPOS_MOLECULE,
	TRIPOS_ATOM,
	TRIPOS_BOND,
	TRIPOS_SUBSTRUCTURE;

	companion object {

		operator fun get(name: String): Section? =
			values().find { it.name == name }
	}
}
