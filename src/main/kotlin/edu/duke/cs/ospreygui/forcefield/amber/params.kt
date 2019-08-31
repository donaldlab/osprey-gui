package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.*
import edu.duke.cs.ospreygui.io.Mol2Metadata
import edu.duke.cs.ospreygui.io.fromMol2WithMetadata
import edu.duke.cs.ospreygui.io.toMol2
import java.util.*


data class AmberTypes(
	val ffnames: List<ForcefieldName>,
	val atomTypes: Map<Atom,String>,
	val bondTypes: Map<AtomPair,String>
) {

	fun toMol2Metadata(mol: Molecule): Mol2Metadata {

		val metadata = Mol2Metadata()

		// copy over the atom and bond types
		metadata.atomTypes.putAll(atomTypes)
		metadata.bondTypes.putAll(bondTypes)

		// use defaults for everything else

		for (atom in mol.atoms) {
			metadata.atomCharges[atom] = Mol2Metadata.defaultCharge
		}

		if (mol is Polymer) {
			for (chain in mol.chains) {
				for (res in chain.residues) {
					metadata.dictionaryTypes[res] = Mol2Metadata.defaultDictionaryType
				}
			}
		}

		return metadata
	}
}

fun Molecule.calcTypesAmber(ffname: ForcefieldName): AmberTypes {

	val dst = this

	// run antechamber to infer all the atom and bond types
	val inMol2 = dst.toMol2()

	val antechamberResults = Antechamber.run(
		inMol2,
		Antechamber.InType.Mol2,
		ffname.atomTypesOrThrow,
		useACDoctor = false
	)
	val outMol2 = antechamberResults.mol2
		?: throw Antechamber.Exception("Antechamber didn't produce an output molecule", inMol2, antechamberResults)

	val (src, srcMetadata) = Molecule.fromMol2WithMetadata(outMol2)

	// HACKHACK: sometimes Antechamber gets the types wrong for proteins
	// no idea why this happens, but just overwrite with the correct answer for these cases
	// TODO: maybe there's a less hacky way to resolve this issue?
	when (ffname.atomTypes) {
		Antechamber.AtomTypes.Amber -> {

			// make sure all the TYR CZ atoms have type C instead of CA
			if (dst is Polymer) {
				for (chain in dst.chains) {
					chain.residues
						.filter { it.type.toUpperCase() == "TYR" }
						.forEach { tyr ->

							tyr.atoms
								.find { it.name.toUpperCase() == "CZ" }
								?.let { dstAtom ->
									val srcAtom = src.atoms[dst.atoms.indexOf(dstAtom)]
									if (srcMetadata.atomTypes[srcAtom] == "CA") {
										srcMetadata.atomTypes[srcAtom] = "C"
									}
								}
						}
				}
			}
		}
		else -> Unit
	}

	// Tragically, we antechamber doesn't write the residue info back into the mol2 file,
	// so we can't use our usual MoleculeMapper to do the atom mapping here.
	// Thankfully, the atom order is preserved, so we can use that to do the mapping instead.
	// val mapper = src.mapTo(dst)
	val atomMap = src.atoms.associateWithTo(IdentityHashMap()) { srcAtom ->
		dst.atoms[src.atoms.indexOf(srcAtom)]
	}

	// translate the atoms back
	val dstMetadata = Mol2Metadata()
	for ((srcAtom, type) in srcMetadata.atomTypes) {
		val dstAtom = atomMap.getValue(srcAtom)
		dstMetadata.atomTypes[dstAtom] = type
	}
	for ((srcBond, type) in srcMetadata.bondTypes) {
		val dsta = atomMap.getValue(srcBond.a)
		val dstb = atomMap.getValue(srcBond.b)
		dstMetadata.bondTypes[AtomPair(dsta, dstb)] = type
	}

	return AmberTypes(listOf(ffname), dstMetadata.atomTypes, dstMetadata.bondTypes)
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
	}

	val combinedTypes = AmberTypes(
		flatMap { (_, types) -> types.ffnames },
		atomTypes,
		bondTypes
	)

	return combinedMol to combinedTypes
}


data class AmberParams(
	/** topology file */
	val top: String,
	/** coordinates file */
	val crd: String
) {

	// TODO: parse the top file for forcefield params?

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

