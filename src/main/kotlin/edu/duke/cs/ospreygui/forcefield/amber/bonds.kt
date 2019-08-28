package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.io.fromMol2
import edu.duke.cs.ospreygui.io.toPDB


/**
 * Uses Amber forecfields to infer atom connectivity,
 * but not bond order.
 */
fun Molecule.inferBondsAmber(): List<Pair<Atom,Atom>> {

	val dst = this
	val dstBonds = ArrayList<Pair<Atom,Atom>>()

	// treat each molecule in the partition with the appropriate forcefield and ambertools
	partition@for ((type, src) in partition(combineSolvent = true)) {

		// TODO: allow user to pick the forcefields?
		val srcBonds = when (type) {

			// treat molecules with either leap or antechamber
			MoleculeType.Protein,
			MoleculeType.DNA,
			MoleculeType.RNA,
			MoleculeType.Solvent -> runLeap(src, type.defaultForcefieldNameOrThrow)

			MoleculeType.SmallMolecule -> runAntechamber(src)

			// atomic ions don't have bonds
			MoleculeType.AtomicIon -> continue@partition

			// synthetics aren't real molecules, just ignore them
			MoleculeType.Synthetic -> continue@partition
		}

		fun Polymer.Residue.translate(): Polymer.Residue {
			val srcRes = this
			val srcChain = (src as Polymer).chains.find { srcRes in it.residues }!!

			return (dst as Polymer).chains.find { it.id == srcChain.id }!!
				.residues.find { it.id == srcRes.id }!!
		}

		fun Atom.translate(): Atom {
			val srcAtom = this

			return if (src is Polymer) {
				val srcRes = src.findResidueOrThrow(srcAtom)
				val dstRes = srcRes.translate()
				dstRes.atoms.find { it.name == srcAtom.name }!!
			} else {
				dst.atoms.find { it.name == srcAtom.name }!!
			}
		}

		// translate the bonds to the input mol
		for ((srcA1, srcA2) in srcBonds) {
			dstBonds.add(srcA1.translate() to srcA2.translate())
		}
	}

	return dstBonds
}

private fun runLeap(mol: Molecule, ffname: ForcefieldName): List<Pair<Atom,Atom>> {

	// run LEaP to infer all the bonds
	val pdb = mol.toPDB()
	val results = Leap.run(
		filesToWrite = mapOf("in.pdb" to pdb),
		commands = """
			|verbosity 2
			|source leaprc.${ffname.name}
			|mol = loadPDB in.pdb
			|saveMol2 mol out.mol2 0
		""".trimMargin(),
		filesToRead = listOf("out.mol2")
	)

	val bondedMol = Molecule.fromMol2(results.files["out.mol2"]
		?: throw Leap.Exception("LEaP didn't produce an output molecule", pdb, results))

	return bondedMol.translateBonds(mol)
}

private fun runAntechamber(mol: Molecule): List<Pair<Atom,Atom>> {

	// run antechamber to infer all the bonds
	val pdb = mol.toPDB()
	val results = Antechamber.run(pdb, Antechamber.InType.Pdb, Antechamber.AtomTypes.SYBYL)
	val bondedMol = Molecule.fromMol2(results.mol2
		?: throw Antechamber.Exception("Antechamber didn't produce an output molecule", pdb, results))

	return bondedMol.translateBonds(mol)
}

private fun Molecule.translateBonds(dst: Molecule): List<Pair<Atom,Atom>> {

	val src: Molecule = this

	val mapper = src.mapTo(dst)

	// map the bonds from src to dst
	return src.bonds
		.toSet()
		.mapNotNull { (srcA1, srcA2) ->
			val dstA1 = mapper.mapAtom(srcA1) ?: return@mapNotNull null
			val dstA2 = mapper.mapAtom(srcA2) ?: return@mapNotNull null
			dstA1 to dstA2
		}
}
