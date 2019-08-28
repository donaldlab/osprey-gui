package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.io.fromMol2
import edu.duke.cs.ospreygui.io.toPDB


/**
 * Uses Amber forecfields to infer missing heavy atoms and their positions.
 */
fun Molecule.inferMissingAtomsAmber(): List<Pair<Atom,Polymer.Residue?>> {

	val dst = this
	val dstAtoms = ArrayList<Pair<Atom,Polymer.Residue?>>()

	// treat each molecule in the partition with the appropriate forcefield and ambertools
	partition@for ((type, src) in partition(combineSolvent = true)) {

		// TODO: allow user to pick the forcefields?
		val srcAtoms = when (type) {

			// treat regular molecules with leap
			MoleculeType.Protein,
			MoleculeType.DNA,
			MoleculeType.RNA -> runLeap(src, type.defaultForcefieldNameOrThrow)

			// ignore everything else
			else -> continue@partition
		}

		fun Polymer.Residue.translate(): Polymer.Residue {
			val srcRes = this
			val srcChain = (src as Polymer).chains.find { srcRes in it.residues }!!

			return (dst as Polymer).chains.find { it.id == srcChain.id }!!
				.residues.find { it.id == srcRes.id }!!
		}

		for ((atom, srcRes) in srcAtoms) {
			val dstRes = srcRes?.translate()
			dstAtoms.add(atom to dstRes)
		}
	}

	return dstAtoms
}


private fun runLeap(mol: Molecule, ffname: ForcefieldName): List<Pair<Atom,Polymer.Residue?>> {

	// run LEaP to infer all the missing atoms
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

	val src = Molecule.fromMol2(results.files["out.mol2"]
		?: throw Leap.Exception("LEaP didn't produce an output molecule", pdb, results))

	val dst = mol
	val mapper = src.mapTo(dst)

	// find all the added heavy atoms
	val dstAtoms = ArrayList<Pair<Atom,Polymer.Residue?>>()
	if (src is Polymer) {

		for (srcChain in src.chains) {
			for (srcRes in srcChain.residues) {
				val dstRes = mapper.mapResidue(srcRes)

				srcRes.atoms
					.filter { atom -> atom.element != Element.Hydrogen }
					.filter { atom -> dstRes.atoms.none { it.name == atom.name } }
					.forEach { atom ->
						dstAtoms.add(atom to dstRes)
					}
			}
		}

	} else {
		throw UnsupportedOperationException("we only need this for polymers, right?")
	}

	return dstAtoms
}
