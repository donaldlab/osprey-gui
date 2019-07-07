package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import org.joml.Vector3d
import java.util.*
import kotlin.NoSuchElementException
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.min


object TopIO {

	/**
	 * Read an AMBER topology file (eg .top)
	 */
	fun read(content: String): AmberTopology {

		val lines = content.lines()

		// pick out the flags we're interested in
		val flags = HashMap<Flag,FlagInfo>()
		var i = 0
		fun peek() = lines[i]
		fun next() = lines[i].also { i += 1 }
		while (i < lines.size) {

			val line = next()
			if (line.startsWith("%FLAG ")) {

				// is this a flag we recognize?
				val flag = Flag[line.substring(6).trim()] ?: continue

				// look ahead to the format line
				val fmtline = next().trim()
				val fmt: Any = if (fmtline.startsWith("%FORMAT(")) {
					val fmtspec = fmtline.substring(8, fmtline.length - 1)
					if (fmtspec.contains('a')) {
						StringFormat.of(fmtspec)
					} else if (fmtspec.contains('E')) {
						DoubleFormat.of(fmtspec)
					} else if (fmtspec.contains('I')) {
						IntFormat.of(fmtspec)
					} else {
						throw ParseException("unrecognized FORMAT: $fmtspec")
					}
				} else {
					throw ParseException("expected FORMAT on line $i, but got: $fmtline")
				}

				// grab the lines for this flag
				val flagLines = ArrayList<String>()
				while (!peek().startsWith("%")) {
					next()
						.takeUnless { it.isBlank() }
						?.let { flagLines.add(it) }
				}
				flags[flag] = FlagInfo(flagLines, fmt)
			}
		}

		fun get(flag: Flag) = flags[flag] ?: throw NoSuchElementException("missing flag: $flag")

		// parse the flags
		return AmberTopology(
			get(Flag.ATOM_NAME).readStrings(),
			get(Flag.ATOMIC_NUMBER).readInts(),
			get(Flag.RESIDUE_POINTER).readInts(),
			get(Flag.RESIDUE_LABEL).readStrings(),
			get(Flag.BONDS_INC_HYDROGEN).readInts(),
			get(Flag.BONDS_WITHOUT_HYDROGEN).readInts(),
			get(Flag.CHARGE).readDoubles(),
			get(Flag.ATOM_TYPE_INDEX).readInts(),
			get(Flag.NONBONDED_PARM_INDEX).readInts(),
			get(Flag.LENNARD_JONES_ACOEF).readDoubles(),
			get(Flag.LENNARD_JONES_BCOEF).readDoubles()
		)
	}
}

/**
 * A representation of the parts of the AMBER topology file format
 * that we care about in OSPREY.
 *
 * Full docs at: http://ambermd.org/FileFormats.php
 */
class AmberTopology(

	/**
	 * %FORMAT(20a4)  (IGRAPH(i), i=1,NATOM)
	 * IGRAPH : the user-specified atoms names
	 */
	val atomNames: List<String>,

	/**
	 * %FORMAT(10I8)  (ATNUM(i), i=1,NATOM)
	 * ATNUM : the atomic number of each atom.
	 */
	val atomicNumbers: List<Int>,

	/**
	 * %FORMAT(10I8)  (IPRES(i), i=1,NRES)
	 * IPRES  : atoms in each residue are listed for atom "i" in
	 *          IPRES(i) to IPRES(i+1)-1
	 */
	val residuePointers: List<Int>,

	/**
	 * %FORMAT(20A4)  (LBRES(i), i=1,NRES)
	 * LBRES : names of each of the residues
	 */
	val residueLabels: List<String>,

	/**
	 * %FORMAT(10I8)  (IBH(i),JBH(i),ICBH(i), i=1,NBONH)
	 * IBH    : atom involved in bond "i", bond contains hydrogen
	 * JBH    : atom involved in bond "i", bond contains hydrogen
	 * ICBH   : index into parameter arrays RK and REQ
	 */
	val bondsIncHydrogen: List<Int>,

	/**
	 * %FORMAT(10I8)  (IB(i),JB(i),ICB(i), i=1,NBONA)
	 * IB     : atom involved in bond "i", bond does not contain hydrogen
	 * JB     : atom involved in bond "i", bond does not contain hydrogen
	 * ICB    : index into parameter arrays RK and REQ
	 */
	val bondsWithoutHydrogen: List<Int>,

	/**
	 * %FORMAT(5E16.8)  (CHARGE(i), i=1,NATOM)
	 * CHARGE : the atom charges.  Amber internally uses units of charge such
	 *          that E = q1*q2/r, where E is in kcal/mol, r is in Angstrom,
	 *          and q1,q2 are the values found in this section of the prmtop file.
	 */
	val charges: List<Double>,

	/**
	 * %FORMAT(1OI8)  (IAC(i), i=1,NATOM)
	 * IAC    : index for the atom types involved in Lennard Jones (6-12)
	 *          interactions.  See ICO below.
	 */
	val atomTypeIndices: List<Int>,

	/**
	 * %FORMAT(10I8)  (ICO(i), i=1,NTYPES*NTYPES)
	 * ICO    : provides the index to the nonbon parameter
	 * arrays CN1, CN2 and ASOL, BSOL.  All possible 6-12
	 * or 10-12 atoms type interactions are represented.
	 * NOTE: A particular atom type can have either a 10-12
	 * or a 6-12 interaction, but not both.  The index is
	 * calculated as follows:
	 * index = ICO(NTYPES*(IAC(i)-1)+IAC(j))
	 * If index is positive, this is an index into the
	 * 6-12 parameter arrays (CN1 and CN2) otherwise it
	 * is an index into the 10-12 parameter arrays (ASOL
	 * and BSOL).
	 */
	val nonbondedParmIndices: List<Int>,

	/**
	 * %FORMAT(5E16.8)  (CN1(i), i=1,NTYPES*(NTYPES+1)/2)
	 * CN1  : Lennard Jones r**12 terms for all possible atom type interactions,
	 * indexed by ICO and IAC; for atom i and j where i < j, the index
	 * into this array is as follows (assuming the value of ICO(index) is
	 * positive): CN1(ICO(NTYPES*(IAC(i)-1)+IAC(j))).
	 */
	val lennardJonesACoeff: List<Double>,

	/**
	 * %FORMAT(5E16.8)  (CN2(i), i=1,NTYPES*(NTYPES+1)/2)
	 * CN2  : Lennard Jones r**6 terms for all possible
	 * atom type interactions.  Indexed like CN1 above.
	 */
	val lennardJonesBCoeff: List<Double>

) {

	init {
		// do some internal consistency checks
		if (residuePointers.size != residueLabels.size) {
			throw IllegalStateException("corrupted topology: inconsistent residue counts: ${residuePointers.size} : ${residueLabels.size}")
		}
	}

	fun mapTo(mol: Molecule): Mapped {

		// make a bi-directional mapping between the molecule atoms and the toplogy atoms
		// the topology will have extra atoms, but none should be missing

		val molToTop = IdentityHashMap<Atom,Int>()
		val topToMol = HashMap<Int,Pair<Atom?,Polymer.Residue?>>()

		// check the number of residues
		val numResidues = if (mol is Polymer) {
			mol.chains.sumBy { it.residues.size }
		} else {
			1
		}
		if (numResidues != residuePointers.size) {
			throw MappingException("residues mismatch: molecule has $numResidues, but topology has ${residuePointers.size}")
		}

		when (numResidues) {
			0 -> throw IllegalArgumentException("topology has no residues")
			1 -> {

				// just one residue, so we must have a small molecule
				for (i in 0 until atomNames.size) {
					val atom = mol.atoms.find { it.name == atomNames[i] }
					if (atom != null) {
						molToTop[atom] = i
						topToMol[i] = atom to null
					} else {
						topToMol[i] = null to null
					}
				}

				// make sure we found all the atoms
				val missingAtoms = mol.atoms.filter { it !in molToTop.keys }
				if (missingAtoms.isNotEmpty()) {
					throw IllegalArgumentException("topology does not describe all atoms in the molecule:\n$missingAtoms")
				}
			}
			else -> {

				// multiple residues, we should have a polymer
				val polymer = mol as? Polymer ?:
					throw IllegalArgumentException("topology has multiple residues, but molecule is not a polymer")

				var iRes = 0
				for (chain in polymer.chains) {
					for (res in chain.residues) {

						// get the atom range
						val iAtoms = residuePointers[iRes] - 1 until
							if (iRes < residuePointers.size - 1) {
								residuePointers[iRes + 1] - 1
							} else {
								atomNames.size
							}
						for (i in iAtoms) {
							val atom = res.atoms.find { it.name == atomNames[i] }
							if (atom != null) {
								molToTop[atom] = i
								topToMol[i] = atom to res
							} else {
								topToMol[i] = null to res
							}
						}

						// make sure we found all the atoms in this residue
						val missingAtoms = res.atoms.filter { it !in molToTop.keys }
						if (missingAtoms.isNotEmpty()) {
							throw IllegalArgumentException("topology does not describe all atoms in residue ${res.id} ${res.type}:\n$missingAtoms")
						}

						iRes += 1
					}
				}

			}
		}

		return Mapped(mol, molToTop, topToMol)
	}

	inner class Mapped internal constructor(
		val mol: Molecule,
		private val molToTop: IdentityHashMap<Atom,Int>,
		private val topToMol: HashMap<Int,Pair<Atom?,Polymer.Residue?>>
	) {

		private fun index(atom: Atom) =
			molToTop[atom]
			?: throw NoSuchElementException("atom is not in the topology")

		fun charge(atom: Atom) = charges[index(atom)]

		/**
		 * Adds all the extra atoms defined by AMBER.
		 *
		 * Returns the number of atoms added.
		 */
		fun addMissingAtoms(coords: List<Vector3d>): Int {

			var numAtomsAdded = 0

			for ((i, atomRes) in topToMol) {
				val (atom, res) = atomRes

				// skip atoms that already exist in the mol
				if (atom != null) {
					continue
				}

				val newAtom = Atom(
					Element[atomicNumbers[i]],
					atomNames[i],
					Vector3d(coords[i])
				)

				// update the molecule (and residue if needed)
				mol.atoms.add(newAtom)
				res?.atoms?.add(newAtom)

				// update the map
				molToTop[newAtom] = i
				topToMol[i] = newAtom to res

				numAtomsAdded += 1
			}

			return numAtomsAdded
		}

		/**
		 * Reset all bonds in the molecule to only those defined by the AMBER topology.
		 *
		 * Returns the number of bonds added.
		 */
		fun setBonds(): Int {

			var numBondsAdded = 0

			// combine all the bonds together, we don't need to treat hydrogens specially
			val bonds = bondsIncHydrogen + bondsWithoutHydrogen
			if (bonds.size % 3 != 0) {
				throw IllegalStateException("unexpected number of bond indices: ${bonds.size}, should be a multiple of 3")
			}

			// iterate over the bonds
			val numBonds = bonds.size/3
			for (i in 0 until numBonds) {

				// lookup the atoms
				val (a1, _) = topToMol[bonds[i*3]] ?: continue
				val (a2, _) = topToMol[bonds[i*3 + 1]] ?: continue
				a1 ?: continue
				a2 ?: continue

				// make the bond
				val wasAdded = mol.bonds.add(a1, a2)
				if (wasAdded) {
					numBondsAdded += 1
				}
			}

			return numBondsAdded
		}
	}
}

class MappingException(msg: String) : RuntimeException(msg)


private enum class Flag {

	ATOM_NAME,
	ATOMIC_NUMBER,
	RESIDUE_POINTER,
	RESIDUE_LABEL,
	BONDS_INC_HYDROGEN,
	BONDS_WITHOUT_HYDROGEN,
	CHARGE,
	ATOM_TYPE_INDEX,
	NONBONDED_PARM_INDEX,
	LENNARD_JONES_ACOEF,
	LENNARD_JONES_BCOEF;

	companion object {

		operator fun get(name: String): Flag? =
			values().find { it.name == name }
	}
}

private data class StringFormat(
	val numPerLine: Int,
	val size: Int
) {

	fun parseLine(line: String, into: MutableList<String>) {
		val num = min(line.length/size, numPerLine)
		for (i in 0 until num) {
			into.add(line.substring(i*size, (i+1)*size).trim())
		}
	}

	companion object {
		fun of(spec: String): StringFormat {
			val parts = spec.split('a')
			return StringFormat(
				parts[0].toInt(),
				parts[1].toInt()
			)
		}
	}
}

private data class DoubleFormat(
	val numPerLine: Int,
	val size: Int,
	val precision: Int
) {

	fun parseLine(line: String, into: MutableList<Double>) {
		val num = min(line.length/size, numPerLine)
		for (i in 0 until num) {
			into.add(line.substring(i*size, (i+1)*size).trim().toDouble())
		}
	}

	companion object {
		fun of(spec: String): DoubleFormat {
			val parts = spec.split('E', '.')
			return DoubleFormat(
				parts[0].toInt(),
				parts[1].toInt(),
				parts[2].toInt()
			)
		}
	}
}

private data class IntFormat(
	val numPerLine: Int,
	val size: Int
) {
	fun parseLine(line: String, into: MutableList<Int>) {
		val num = min(line.length/size, numPerLine)
		for (i in 0 until num) {
			into.add(line.substring(i*size, (i+1)*size).trim().toInt())
		}
	}

	companion object {
		fun of(spec: String): IntFormat {
			val parts = spec.split('I', '.')
			return IntFormat(
				parts[0].toInt(),
				parts[1].toInt()
			)
		}
	}
}

private data class FlagInfo(
	val lines: List<String>,
	val format: Any
) {
	fun readStrings() = ArrayList<String>().apply {
		val format = format as StringFormat
		for (line in lines) {
			format.parseLine(line, this)
		}
	}

	fun readDoubles() = ArrayList<Double>().apply {
		val format = format as DoubleFormat
		for (line in lines) {
			format.parseLine(line, this)
		}
	}

	fun readInts() = ArrayList<Int>().apply {
		val format = format as IntFormat
		for (line in lines) {
			format.parseLine(line, this)
		}
	}
}


class ParseException(msg: String) : RuntimeException(msg)
