package edu.duke.cs.ospreygui.io

import cuchaz.kludge.tools.x
import cuchaz.kludge.tools.y
import cuchaz.kludge.tools.z
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.osprey.structure.Molecule as OspreyMolecule
import edu.duke.cs.osprey.structure.Atom as OspreyAtom
import edu.duke.cs.osprey.structure.Residue as OspreyResidue
import edu.duke.cs.osprey.structure.PDBIO
import java.util.*
import kotlin.math.min


/**
 * Save the molecule to PDB format.
 */
fun Molecule.toPDB(): String {
	return PDBIO.write(toOspreyMol())
}


/**
 * Read a molecule in PDB format.
 */
fun Molecule.Companion.fromPDB(pdb: String): Molecule {
	return PDBIO.read(pdb).toMolecule("PDB")
}


/**
 * Convert an OSPREY molecule to a Molscope molecule.
 */
fun OspreyMolecule.toMolecule(name: String): Molecule {

	val omol = this

	// if there's more than one residue, assume it's a polymer
	val mol = if (omol.residues.size > 1) {
		Polymer(name)
	} else {
		Molecule(name)
	}

	// convert the atoms
	val atomMap = IdentityHashMap<OspreyAtom,Atom>()
	for (ores in omol.residues) {
		for (oatom in ores.atoms) {
			val atom = Atom(
				Element.get(oatom.elementNumber),
				oatom.name,
				oatom.coords[0],
				oatom.coords[1],
				oatom.coords[2]
			)
			atomMap[oatom] = atom
			mol.atoms.add(atom)
		}
	}

	// convert the bonds
	for (ores in omol.residues) {
		for (oatom in ores.atoms) {
			val atom = atomMap[oatom]!!
			for (bondedOatom in oatom.bonds) {
				val bondedAtom = atomMap[bondedOatom]!!
				mol.bonds.add(atom, bondedAtom)
			}
		}
	}

	if (mol is Polymer) {

		// convert the residues
		for (res in omol.residues) {

			// get/make the chain
			val chain = mol.chains
				.find { it.id == res.chainId.toString() }
				?: run {
					Polymer.Chain(res.chainId.toString()).apply {
						mol.chains.add(this)
					}
				}

			chain.residues.add(Polymer.Residue(
				res.pdbResNumber.substring(1),
				res.type,
				res.atoms.map { atomMap[it]!! }
			))
		}
	}

	return mol
}


/**
 * Convert a Molscope molecule to an OSPREY molecule.
 */
fun Molecule.toOspreyMol(smallMolResType: String? = null): OspreyMolecule {

	val mol = this
	val omol = OspreyMolecule()

	val atomMap = IdentityHashMap<Atom,OspreyAtom>()

	fun Atom.toOsprey(): Pair<OspreyAtom,DoubleArray> {
		val oatom = OspreyAtom(name, element.symbol)
		atomMap[this] = oatom
		return oatom to doubleArrayOf(pos.x, pos.y, pos.z)
	}

	when (mol) {
		is Polymer -> {

			// put the atoms in multiple residues
			for (chain in mol.chains) {
				for (res in chain.residues) {

					val atoms = ArrayList<OspreyAtom>()
					val coords = ArrayList<DoubleArray>()
					for (atom in res.atoms) {
						val (oatom, oatomCoords) = atom.toOsprey()
						atoms.add(oatom)
						coords.add(oatomCoords)
					}

					// build the residue "full name", eg "ASN A  23"
					val fullName = "%3s%2s%4s".format(
						res.type.substring(0, min(res.type.length, 3)),
						chain.id[0],
						res.id.substring(0, min(res.id.length, 4))
					)
					omol.residues.add(OspreyResidue(atoms, coords, fullName, omol))
				}
			}
		}
		else -> {

			// put all the atoms in a single residue
			val atoms = ArrayList<OspreyAtom>()
			val coords = ArrayList<DoubleArray>()
			for (atom in mol.atoms) {
				val (oatom, oatomCoords) = atom.toOsprey()
				atoms.add(oatom)
				coords.add(oatomCoords)
			}

			// build the residue "full name", eg "ASN A  23"
			val fullName = "%3s%2s%4s".format(
				smallMolResType
					?: omol.name?.substring(0, min(omol.name.length, 3))
					?: "MOL",
				'A',
				"1"
			)
			omol.residues.add(OspreyResidue(atoms, coords, fullName, omol))
		}
	}

	// transfer the bonds
	for ((atom, oatom) in atomMap) {
		for (bonded in mol.bonds.bondedAtoms(atom)) {
			val obonded = atomMap[bonded]
			if (obonded !in oatom.bonds) {
				oatom.addBond(obonded)
			}
		}
	}

	return omol
}
