package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import kotlin.collections.ArrayList
import kotlin.math.min


// these residue types match the residue types used in AmberTools19 pdb4amber

private val aminoAcidResTypes = setOf(
	"ALA", "A",
	"ARG", "R",
	"ASN", "N",
	"ASP", "D", "ASH", "AS4",
	"CYS", "C", "CYM", "CYX",
	"GLU", "E", "GLH", "GL4",
	"GLN", "Q",
	"GLY", "G",
	"HIS", "H", "HIP", "HIE", "HID",
	"HYP",
	"ILE", "I",
	"LEU", "L",
	"LYS", "K", "LYN",
	"MET", "M",
	"PHE", "F",
	"PRO", "P",
	"SER", "S",
	"THR", "T",
	"TRP", "W",
	"TYR", "Y",
	"VAL", "V"
)

private val dnaResTypes = setOf(
	"DG", "GUA", "DG5", "DG3", "DGN",
	"DC", "CYT", "DC5", "DC3", "DCN", "DCP",
	"DA", "ADE", "DA5", "DA3", "DAN", "DAP",
	"DT", "THY", "DT5", "DT3"
)

private val rnaResTypes = setOf(
	"G", "GUA", "G5", "G3", "GN", "RG", "RG3", "RG5", "RGN", "GF2", "M2G", "YYG", "7MG", "OMG", "2MG",
	"C", "CYT", "CP", "C5", "C3", "CN", "RC", "RC5", "RC3", "RCN", "CFZ", "5MC", "OMC",
	"A", "ADE", "AP", "A5", "A3", "AN", "RA", "RA3", "RA5", "AF2", "1MA",
	"U", "URA", "U3", "U5", "UN", "RU", "RU3", "RU5", "RUN", "UFT", "5MU", "H2U", "PSU",
	"T", "THY", "T3", "T5", "TN", "RT", "RT3", "RT5", "RTN"
)

private val solventResTypes = setOf(
	"WAT", "HOH", "TIP3", "TIP4", "TIP5", "SPCE", "SPC", "SOL"
)

private val ionResTypes = setOf(
	"Na+", "Li+", "Mg+", "Rb+", "MG", "Cs+", "POT", "SOD", "MG2",
	"CAL", "RUB", "LIT", "ZN2", "CD2", "NA", "K+", "K", "NA+",
	"Cl-", "Br-", "F-", "I-", "CLA", "CL", "BR", "CL-"
)

private val syntheticResTypes = setOf(
	// amber calls these "extra points"
	// also, they're album types
	"EP", "LP"
)

enum class MoleculeType(val isPolymer: Boolean) {

	Protein(true),
	DNA(true),
	RNA(true),
	Solvent(false),
	Ion(false),
	Synthetic(false),
	SmallMolecule(false);

	companion object {
		operator fun get(resType: String) =
			when {

				// all the usual stuff
				aminoAcidResTypes.contains(resType) -> Protein
				dnaResTypes.contains(resType) -> DNA
				rnaResTypes.contains(resType) -> RNA
				solventResTypes.contains(resType) -> Solvent
				ionResTypes.contains(resType) -> Ion

				// we can typically ignore these, since they don't represent real molecules
				syntheticResTypes.contains(resType) -> Synthetic

				// assume anything we don't know is a small molecule
				else -> SmallMolecule
			}
	}
}

/**
 * Partition a single Molecule into a list of Molecules
 * based on AMBER rules for residue classification.
 *
 * Ignores all bonds.
 */
fun Molecule.partition(): List<Pair<MoleculeType,Molecule>> {

	// for non-polymers, assume the whole molecule is a small molecule
	if (this !is Polymer) {
		return listOf(MoleculeType.SmallMolecule to this)
	}

	data class Partitioned(
		val moltype: MoleculeType,
		val chainId: String,
		val residues: List<Polymer.Residue>
	)

	// create the partition
	val partition = ArrayList<Partitioned>()
	for (chain in chains) {

		// chains in PDB files typically have lots of different molecules in them,
		// so separate them out by contiguous moltypes in PDB residue order
		var currentMoltype: MoleculeType? = null
		var residues: MutableList<Polymer.Residue>? = null
		for (res in chain.residues) {

			// if the moltype changed
			val moltype = MoleculeType[res.type]
			if (moltype != currentMoltype) {
				currentMoltype = moltype

				// start a new group of residues
				residues = ArrayList()
				residues.add(res)
				partition.add(Partitioned(moltype, chain.id, residues))

			} else {

				// otherwise, append to the existing group
				residues!!.add(res)
			}
		}
	}

	// create a molecule for each item in the partition
	return partition.flatMap { (moltype, chainId, residues) ->
		if (moltype.isPolymer) {

			// map all the residues to a new polymer
			val polymer = Polymer(moltype.name)
			val chain = Polymer.Chain(chainId)
			for (res in residues) {
				val resAtoms = res.atoms.map { it.copy() }
				polymer.atoms.addAll(resAtoms)
				chain.residues.add(Polymer.Residue(res.id, res.type, resAtoms))
			}
			polymer.chains.add(chain)

			return@flatMap listOf(moltype to polymer)

		} else {

			// map each residue to a new molecule
			return@flatMap residues.map { res ->
				val mol = Molecule(moltype.name, res.type)

				// copy the atoms
				mol.atoms.addAll(res.atoms.map { it.copy() })

				return@map moltype to mol
			}
		}
	}
}

/**
 * Combine multiple Molecules into a single Molecule,
 * taking care to assign each Polymer in the list a unique chain id.
 *
 * Ignores all bonds.
 */
fun Collection<Molecule>.combine(name: String): Molecule {

	// if it's just one molecule that's not a polymer, just return a copy of that molecule
	if (size == 1) {
		firstOrNull()?.let { mol ->
			if (mol !is Polymer) {
				return mol.copy()
			}
		}
	}

	val out = Polymer(name)

	// figure out all the chain ids
	var nextChainId = 'A'
	fun getNextChainId() = "${nextChainId++}"
	val chainIds = this
		.filterIsInstance<Polymer>()
		.flatMap { it.chains }
		.associateWith { getNextChainId() }

	val nonPolymerChain = Polymer.Chain(getNextChainId())

	fun String.first(len: Int) = substring(0, min(len, length))

	// combine the molecules
	var nextResId = 1
	for (mol in this) {
		if (mol is Polymer) {

			// copy over each chain from the polymer
			for (chain in mol.chains) {
				val outChain = Polymer.Chain(chainIds.getValue(chain))
				for (res in chain.residues) {
					val outAtoms = res.atoms.map { it.copy() }
					outChain.residues.add(Polymer.Residue(
						res.id,
						res.type,
						outAtoms
					))
					out.atoms.addAll(outAtoms)
				}
				out.chains.add(outChain)
			}

		} else {

			// convert the molecule into a chain
			val outRes = Polymer.Residue(
				"${nextResId++}",
				mol.type ?: mol.name.first(3).toUpperCase(),
				mol.atoms.map { it.copy() }
			)
			nonPolymerChain.residues.add(outRes)
			out.atoms.addAll(outRes.atoms)
		}
	}

	out.chains.add(nonPolymerChain)
	return out
}
