package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.*
import edu.duke.cs.molscope.tools.associateIdentity
import edu.duke.cs.ospreygui.io.*
import java.util.*


data class AmberTypes(
	val ffnames: List<ForcefieldName>,
	val atomTypes: Map<Atom,String>,
	val bondTypes: Map<AtomPair,String>,
	val atomCharges: Map<Atom,String>
) {

	constructor(ffnames: List<ForcefieldName>, mol2Metadata: Mol2Metadata)
		: this(ffnames, mol2Metadata.atomTypes, mol2Metadata.bondTypes, mol2Metadata.atomCharges)

	fun toMol2Metadata(mol: Molecule): Mol2Metadata {

		val metadata = Mol2Metadata()

		// copy over the atom and bond types
		metadata.atomTypes.putAll(atomTypes)
		metadata.bondTypes.putAll(bondTypes)
		metadata.atomCharges.putAll(atomCharges)

		if (mol is Polymer) {
			for (chain in mol.chains) {
				for (res in chain.residues) {
					metadata.dictionaryTypes[res] = Mol2Metadata.defaultDictionaryType
				}
			}
		}

		return metadata
	}

	/**
	 * Returns a copy of this class, but keys all the maps
	 * with equivalent atoms in the destination molecule.
	 */
	fun transferTo(dst: Molecule): AmberTypes {

		val dstAtoms = dst.atoms.associateWith { it }
		fun Atom.match() = dstAtoms[this]
			?: throw NoSuchElementException("destination molecule doesn't have equivalent atom for $this")

		return AmberTypes(
			ffnames = ffnames,
			atomTypes = atomTypes
				.mapKeys { (srcAtom, _) ->
					srcAtom.match()
				},
			bondTypes = bondTypes
				.mapKeys { (srcBond, _) ->
					AtomPair(srcBond.a.match(), srcBond.b.match())
				},
			atomCharges = atomCharges
				.mapKeys { (srcAtom, _) ->
					srcAtom.match()
				}
		)
	}
}

/**
 * Calculate Amber types for atoms and bonds.
 * Pass values to `chargeMethod` and `netCharge` to calculate partial charges for small molecules as well.
 */
fun Molecule.calcTypesAmber(
	molType: MoleculeType,
	ffname: ForcefieldName = molType.defaultForcefieldNameOrThrow,
	chargeMethod: Antechamber.ChargeMethod? = null,
	netCharge: Int? = null,
	sqmOptions: SQM.Options = SQM.Options()
): AmberTypes {

	val dst = this

	val (srcMetadata, atomMap) = when (molType) {

		// use antechamber to get types for small molecules
		MoleculeType.SmallMolecule -> {

			val inMol2 = dst.toMol2()

			val antechamberResults = Antechamber.run(
				inMol2,
				Antechamber.InType.Mol2,
				ffname.atomTypesOrThrow,
				useACDoctor = false,
				generateCharges = chargeMethod,
				netCharge = netCharge,
				sqmOptions = sqmOptions
			)

			// read the resulting mol2 file
			val outMol2 = antechamberResults.mol2
				?: throw Antechamber.Exception("Antechamber didn't produce an output molecule", inMol2, antechamberResults)
			val (src, srcMetadata) = Molecule.fromMol2WithMetadata(outMol2)

			// Tragically, we antechamber doesn't write the residue info back into the mol2 file,
			// so we can't use our usual MoleculeMapper to do the atom mapping here.
			// Thankfully, the atom order is preserved, so we can use that to do the mapping instead.
			// val mapper = src.mapTo(dst)
			srcMetadata to src.atoms.associateIdentity { srcAtom ->
				srcAtom to dst.atoms[src.atoms.indexOf(srcAtom)]
			}
		}

		// for everything else, use LEaP
		else -> {

			val pdb = dst.toPDB()

			val results = Leap.run(
				filesToWrite = mapOf("in.pdb" to pdb),
				commands = """
					|verbosity 2
					|source leaprc.${ffname.name}
					|mol = loadPDB in.pdb
					|saveMol2 mol out.mol2 1
				""".trimMargin(),
				filesToRead = listOf("out.mol2")
			)

			val outMol2 = results.files["out.mol2"]
				?: throw Leap.Exception("LEaP didn't produce an output molecule", pdb, results)
			val (src, srcMetadata) = Molecule.fromMol2WithMetadata(outMol2)

			// check for unmapped atoms with the dst->src mapping
			MoleculeMapper(dst, src)
				.let { dstToSrc ->
					dst.atoms.filter { dstToSrc.mapAtom(it) == null }
				}
				.takeIf { it.isNotEmpty() }
				?.map { atom ->
					// get descriptive info for the atom
					(dst as? Polymer)
						?.findChainAndResidue(atom)
						?.let { (chain, res) ->
							"${atom.name} @ ${chain.id}${res.id}"
						}
						?: atom.name
				}
				?.let { unmappedAtoms ->
					throw Leap.Exception("LEaP didn't generate atom info for ${unmappedAtoms.size} atom(s):\n$unmappedAtoms", pdb, results)
				}

			// use the molecule mapper to map the metadata back with the src->dst mapping
			val srcToDst = MoleculeMapper(src, dst)
			srcMetadata to src.atoms.associateIdentity { srcAtom ->
				srcAtom to srcToDst.mapAtom(srcAtom)
			}
		}
	}

	// translate the metadata back
	val dstMetadata = Mol2Metadata()
	for ((srcAtom, type) in srcMetadata.atomTypes) {
		val dstAtom = atomMap.getValue(srcAtom) ?: continue
		dstMetadata.atomTypes[dstAtom] = type
	}
	for ((srcBond, type) in srcMetadata.bondTypes) {
		val dsta = atomMap.getValue(srcBond.a) ?: continue
		val dstb = atomMap.getValue(srcBond.b) ?: continue
		dstMetadata.bondTypes[AtomPair(dsta, dstb)] = type
	}
	for ((srcAtom, charge) in srcMetadata.atomCharges) {
		val dstAtom = atomMap.getValue(srcAtom) ?: continue
		dstMetadata.atomCharges[dstAtom] = charge
	}

	return AmberTypes(listOf(ffname), dstMetadata)
}


fun Molecule.calcModsAmber(types: AmberTypes): String? {

	val mol2 = toMol2(types.toMol2Metadata(this))
	val ffname = types.ffnames
		.takeIf { it.size == 1 }
		?.first()
		?: throw IllegalArgumentException("must have only a single forcefield name to calculate forcefield modifications")
	val atomTypes = Parmchk.AtomTypes.from(ffname) ?: return null

	val results = Parmchk.run(mol2, atomTypes)

	if (results.frcmod == null) {
		throw Parmchk.Exception("No results generated", mol2, results)
	}

	return results.frcmod
}

fun List<Pair<Molecule,AmberTypes>>.combine(): Pair<Molecule,AmberTypes> {

	val (combinedMol, atomMap ) =
		map { (mol, _) -> mol }
		.combine("combined")

	val atomTypes = IdentityHashMap<Atom,String>()
	val bondTypes = HashMap<AtomPair,String>()
	val atomCharges = IdentityHashMap<Atom,String>()
	for ((_, types) in this) {

		for ((atom, type) in types.atomTypes) {
			atomTypes[atomMap.getBOrThrow(atom)] = type
		}

		for ((bond, type) in types.bondTypes) {
			bondTypes[AtomPair(
				atomMap.getBOrThrow(bond.a),
				atomMap.getBOrThrow(bond.b)
			)] = type
		}

		for ((atom, charge) in types.atomCharges) {
			atomCharges[atomMap.getBOrThrow(atom)] = charge
		}
	}

	val combinedTypes = AmberTypes(
		flatMap { (_, types) -> types.ffnames },
		atomTypes,
		bondTypes,
		atomCharges
	)

	return combinedMol to combinedTypes
}


data class AmberParams(
	/** topology file */
	val top: String,
	/** coordinates file */
	val crd: String
) {

	companion object {

		fun from(mol2s: List<String>, ffnames: List<ForcefieldName>, frcmods: List<String> = emptyList()): AmberParams {

			val molname = "mol.%d.mol2"
			val frcname = "mol.%d.frc"
			val topname = "mol.top"
			val crdname = "mol.crd"

			val commands = ArrayList<String>()
			commands += "verbosity 2"
			for (ffname in ffnames) {
				commands += "source leaprc.${ffname.name}"
			}
			for (i in frcmods.indices) {
				commands += "loadAmberParams ${frcname.format(i)}"
			}
			for (i in mol2s.indices) {
				commands += "mol$i = loadMol2 ${molname.format(i)}"
			}
			commands += if (mol2s.size > 1) {
					"combined = combine { ${mol2s.indices.joinToString(" ") { "mol$it" }} }"
				} else {
					"combined = mol0"
				}
			commands += "saveAmberParm combined $topname, $crdname"

			val filesToWrite = HashMap<String,String>()
			mol2s.forEachIndexed { i, mol2 ->
				filesToWrite[molname.format(i)] = mol2
			}
			frcmods.forEachIndexed { i, frcmod ->
				filesToWrite[frcname.format(i)] = frcmod
			}

			// run LEaP to get the params
			val leapResults = Leap.run(
				filesToWrite,
				commands = commands.joinToString("\n"),
				filesToRead = listOf(topname, crdname)
			)

			return AmberParams(
				leapResults.files[topname] ?: throw Leap.Exception("no topology file", mol2s.joinToString("\n"), leapResults),
				leapResults.files[crdname] ?: throw Leap.Exception("no coordinates file", mol2s.joinToString("\n"), leapResults)
			)
		}
	}
}

fun Molecule.calcParamsAmber(types: AmberTypes, frcmods: List<String> = emptyList()) =
	listOf(this to types).calcParamsAmber(frcmods)

fun List<Pair<Molecule,AmberTypes>>.calcParamsAmber(frcmods: List<String> = emptyList()) =
	AmberParams.from(
		mol2s = map { (mol, types) -> mol.toMol2(types.toMol2Metadata(mol)) },
		ffnames = flatMap { (_, types) -> types.ffnames }
			.toSet()
			.toList(),
		frcmods = frcmods
	)

