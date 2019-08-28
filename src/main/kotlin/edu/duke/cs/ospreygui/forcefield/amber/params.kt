package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.AtomPair
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.io.Mol2Metadata
import edu.duke.cs.ospreygui.io.fromMol2WithMetadata
import edu.duke.cs.ospreygui.io.toMol2
import java.util.*


data class AmberTypes(
	val ffname: ForcefieldName,
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

	// Tragically, we antechamber doesn't write the residue info back into the mol2 file,
	// so we can't use our usual MoleculeMapper to do the atom mapping here.
	// Thankfully, the atom order is preserved, so we can use that to do the mapping instead.
	// val mapper = src.mapTo(dst)
	fun mapAtom(srcAtom: Atom) =
		dst.atoms[src.atoms.indexOf(srcAtom)]

	// translate the atoms back
	val atomTypes = IdentityHashMap<Atom,String>().apply {
		for ((srcAtom, type) in srcMetadata.atomTypes) {
			val dstAtom = mapAtom(srcAtom)
			put(dstAtom, type)
		}
	}
	val bondTypes = HashMap<AtomPair,String>().apply {
		for ((srcBond, type) in srcMetadata.bondTypes) {
			val dsta = mapAtom(srcBond.a)
			val dstb = mapAtom(srcBond.b)
			put(AtomPair(dsta, dstb), type)
		}
	}

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
								?.let { cz ->
									if (atomTypes[cz] == "CA") {
										atomTypes[cz] = "C"
									}
							}
						}
				}
			}
		}
		else -> Unit
	}

	return AmberTypes(ffname, atomTypes, bondTypes)
}


data class AmberParams(
	/** topology file */
	val top: String,
	/** coordinates file */
	val crd: String
) {

	// TODO: parse the top file for forcefield params?
}

fun Molecule.calcParamsAmber(types: AmberTypes): AmberParams {

	val mol2 = toMol2(types.toMol2Metadata(this))

	val molname = "mol.mol2"
	val topname = "mol.top"
	val crdname = "mol.crd"

	// run LEaP to get the params
	val leapResults = Leap.run(
		filesToWrite = mapOf(molname to mol2),
		commands = """
			|verbosity 2
			|source leaprc.${types.ffname.name}
			|mol = loadMol2 $molname
			|saveAmberParm mol $topname, $crdname
		""".trimMargin(),
		filesToRead = listOf(topname, crdname)
	)

	return AmberParams(
		leapResults.files[topname] ?: throw Leap.Exception("no topology file", mol2, leapResults),
		leapResults.files[crdname] ?: throw Leap.Exception("no coordinates file", mol2, leapResults)
	)
}
