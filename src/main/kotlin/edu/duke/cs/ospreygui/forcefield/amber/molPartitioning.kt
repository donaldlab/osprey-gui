package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.molecule.combine
import kotlin.collections.ArrayList


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

private val atomicIonResTypes = setOf(
	"Na+", "Li+", "Mg+", "Rb+", "MG", "Cs+", "POT", "SOD", "MG2",
	"CAL", "RUB", "LIT", "ZN2", "CD2", "NA", "K+", "K", "NA+",
	"Cl-", "Br-", "F-", "I-", "CLA", "CL", "BR", "CL-"
)

private val syntheticResTypes = setOf(
	// amber calls these "extra points"
	// also, they're album types
	"EP", "LP"
)

data class ForcefieldName(
	val name: String,
	val atomTypes: Antechamber.AtomTypes? = null
) {

	val atomTypesOrThrow get() =
		atomTypes ?: throw NoSuchElementException("forcefield $name doesn't have atom types for Antechamber")

	companion object {

		val ff96 = ForcefieldName("ff96", Antechamber.AtomTypes.Amber)
		val ff14SB = ForcefieldName("protein.ff14SB", Antechamber.AtomTypes.Amber)

		val DNAOL15 = ForcefieldName("DNA.OL15", Antechamber.AtomTypes.Amber)
		val RNAOL15 = ForcefieldName("RNA.OL3", Antechamber.AtomTypes.Amber)
		val tip3p = ForcefieldName("water.tip3p", Antechamber.AtomTypes.Amber)

		val gaff = ForcefieldName("gaff", Antechamber.AtomTypes.Gaff)
		val gaff2 = ForcefieldName("gaff2", Antechamber.AtomTypes.Gaff2)
	}
}

enum class MoleculeType(
	val isPolymer: Boolean,
	val forcefieldNames: List<ForcefieldName>
) {

	Protein(true, listOf(
		ForcefieldName.ff96, // dlab's favorite and time-tested protein forecfield
		ForcefieldName.ff14SB // currently recommended by AmberTools19
	)),

	DNA(true, listOf(
		ForcefieldName.DNAOL15 // currently recommended by AmberTools19
	)),

	RNA(true, listOf(
		ForcefieldName.RNAOL15 // currently recommended by AmberTools19
	)),

	Solvent(false, listOf(
		ForcefieldName.tip3p // currently recommended by AmberTools19
	)),

	AtomicIon(false, listOf(
		ForcefieldName.tip3p // currently recommended by AmberTools19
	)),

	Synthetic(false, listOf(
		// not real molecules, no forcefield needed
	)),

	SmallMolecule(false, listOf(
		ForcefieldName.gaff2, // currently recommended by AmberTools19
		ForcefieldName.gaff
	));


	// the default option is the first in the list
	val defaultForcefieldName get() = forcefieldNames.firstOrNull()

	val defaultForcefieldNameOrThrow get() =
		defaultForcefieldName ?: throw NoSuchElementException("molecule type $this has no default forcefield")

	companion object {
		operator fun get(resType: String) =
			when {

				// all the usual stuff
				aminoAcidResTypes.contains(resType) -> Protein
				dnaResTypes.contains(resType) -> DNA
				rnaResTypes.contains(resType) -> RNA
				solventResTypes.contains(resType) -> Solvent
				atomicIonResTypes.contains(resType) -> AtomicIon

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
fun Molecule.partition(combineSolvent: Boolean = true): List<Pair<MoleculeType,Molecule>> {

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
	var mols = partition.flatMap { (moltype, chainId, residues) ->
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

	if (combineSolvent) {

		mols
			.filter { (type, _) -> type == MoleculeType.Solvent }
			.map { (_, mol) -> mol }
			.takeIf { it.isNotEmpty() }
			?.combine(MoleculeType.Solvent.name)
			?.let { (combinedSolvent, _) ->

				mols = mols
					.filter { (type, _) -> type != MoleculeType.Solvent }
					.toMutableList() + listOf(MoleculeType.Solvent to combinedSolvent)
			}
	}

	return mols
}
