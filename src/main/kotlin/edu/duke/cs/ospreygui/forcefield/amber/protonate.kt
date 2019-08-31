package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.io.fromMol2
import edu.duke.cs.ospreygui.io.toMol2
import edu.duke.cs.ospreygui.io.toPDB


/**
 * Get all the supported protonation states for the specified atom.
 */
fun Molecule.protonations(atom: Atom): List<Protonation> {

	// get the number of bonded heavy atoms
	val numHeavy = bonds.bondedAtoms(atom)
		.filter { it.element != Element.Hydrogen }
		.size

	// get all the protonations supported for this atom
	return protonations.keys
		.filter { it.element == atom.element && it.numHeavy == numHeavy }
}

/**
 * Removes all bonded hydrogen atoms from the atom.
 */
fun Molecule.deprotonate(atom: Atom) {

	// remove all the hydrogens
	bonds.bondedAtoms(atom)
		.filter { it.element == Element.Hydrogen }
		.forEach {
			atoms.remove(it)
			if (this is Polymer) {
				findResidue(it)?.atoms?.remove(it)
			}
		}
}

/**
 * Adds hydrogens atoms to the atom in the supplied protonation state.
 */
fun Molecule.protonate(atom: Atom, protonation: Protonation) {

	val mol = this

	// get the GAFF atom types
	val amberTypes = protonations[protonation]
		?: throw IllegalArgumentException("protonation not supported for atom ${atom.name}: $protonation")

	// make a small molecule for just this atom and its heavy neighbors
	val smol = Molecule(mol.name)
		.apply {
			atoms.add(atom)
			mol.bonds.bondedAtoms(atom)
				.filter { it.element != Element.Hydrogen }
				.forEach {
					atoms.add(it)
					bonds.add(atom, it)
				}
		}
		.toMol2()

	// build the LEaP commands
	val commands = ArrayList<String>().apply {

		// show all the info in the console
		add("verbosity 2")

		// load the generalized amber forcefield (for small molecules)
		// TODO: let caller pick the forcefield? (will have to have AmberTypes for each possible choice)
		add("source leaprc.${MoleculeType.SmallMolecule.defaultForcefieldNameOrThrow.name}")

		// read our molecule fragment
		add("mol = loadMol2 in.mol2")

		// set the central atom type
		add("set mol.1.${atom.name} type ${amberTypes.heavy}")

		// do we need to set bond type info?
		if (protonation.numHeavy == 1 && amberTypes.bondedHeavy != null && amberTypes.heavyBond != null) {

			// yup, try to set the bond and atoms types explicitly so we get the desired geometry
			// (leap doesn't recognize Sp hybridization explicitly,
			// and sometimes gets Sp2 hybridization wrong when we don't label the bonds correctly)

			// what's the other heavy atom?
			val heavyAtom = mol.bonds.bondedAtoms(atom)
				.filter { it.element != Element.Hydrogen }
				.takeIf { it.size == 1 }
				?.first()
				?: throw IllegalArgumentException("couldn't find unique heavy bonded atom")

			// set the other heavy atom type
			val heavyAtomType = amberTypes.bondedHeavy[heavyAtom.element]
				?: throw Error("protonation is Sp hybridized, but has no bonded heavy atom type for ${heavyAtom.element}")
			add("set mol.1.${heavyAtom.name} type $heavyAtomType")

			// replace the bond
			add("deleteBond mol.1.${atom.name} mol.1.${heavyAtom.name}")
			add("bond mol.1.${atom.name} mol.1.${heavyAtom.name} ${amberTypes.heavyBond}")
		}

		// get the hydrogens whose names we shouldn't match
		val domainHydrogens = HashMap<String,Int?>().apply {
			if (mol is Polymer) {
				mol.findResidueOrThrow(atom).atoms
			} else {
				mol.atoms
			}
			.filter { it.element == Element.Hydrogen }
			.forEach { atom ->
				this[atom.name] = atom.name
					.filter { it.isDigit() }
					.takeIf { it.isNotBlank() }
					?.toInt()
			}
		}

		// add the hydrogens
		for (i in 0 until protonation.numH) {

			// pick a number for the hydrogen
			// try not to match other hydrogens in the molecule
			val atomNumber = atom.name.filter { it.isDigit() }
			var hNumber = "$atomNumber${i + 1}".toInt()
			if (hNumber in domainHydrogens.values) {
				hNumber = (domainHydrogens.values.maxBy { it ?: 0 } ?: 0) + 1
			}
			val hName = "H$hNumber"
			domainHydrogens[hName] = hNumber

			add("h$i = createAtom $hName ${amberTypes.hydrogen} 0.0")
			add("set h$i element H")
			add("add mol.1 h$i")
			add("bond mol.1.${atom.name} h$i")
			add("select h$i")
		}
		add("rebuildSelectedAtoms mol")

		// save the built molecule
		add("saveMol2 mol out.mol2 0")
	}

	// run LEaP
	val results = Leap.run(
		filesToWrite = mapOf(
			"in.mol2" to smol
		),
		commands = commands.joinToString("\n"),
		filesToRead = listOf("out.mol2"),
		// use the debug build of teLeap, so we get more info in the console
		debugFiles = listOf("model.c")
	)

	// read the output mol2 and copy the new hydrogens
	val hmol = Molecule.fromMol2(results.files["out.mol2"]
		?: throw Leap.Exception("LEaP didn't produce an output molecule", smol, results))
	val centerAtom = hmol.atoms.find { it.name == atom.name }
		?: throw Error("can't find central atom in LEaP output molecule")
	mol.deprotonate(atom)
	val res = (mol as? Polymer)?.findResidue(atom)
	hmol.bonds.bondedAtoms(centerAtom)
		.filter { it.element == Element.Hydrogen }
		.forEach {
			mol.atoms.add(it)
			mol.bonds.add(atom, it)
			res?.atoms?.add(it)
		}
}

/**
 * Returns a list of heavy-hydrogen atom pairs based on inferred
 * forcefield atom and bond types.
 */
fun Molecule.inferProtonation(): List<Pair<Atom,Atom>> {

	val dst = this
	val dstAtoms = ArrayList<Pair<Atom,Atom>>()

	// treat each molecule in the partition with the appropriate protocol
	val (partition, atomMap) = dst.partitionAndAtomMap(combineSolvent = true)
	partition@for ((type, src) in partition) {

		// TODO: allow user to pick the forcefields?
		val srcAtoms = when (type) {

			// treat molecules with either leap or antechamber
			MoleculeType.Protein,
			MoleculeType.DNA,
			MoleculeType.RNA,
			MoleculeType.Solvent -> inferProtonationLeap(src, type.defaultForcefieldNameOrThrow)

			MoleculeType.SmallMolecule -> inferProtonationAntechamberThenLeap(src, type.defaultForcefieldNameOrThrow)

			// atomic ions don't have protonation
			MoleculeType.AtomicIon -> continue@partition

			// synthetics aren't real molecules, just ignore them
			MoleculeType.Synthetic -> continue@partition
		}

		// translate the atoms to the input mol
		for ((srcHeavy, h) in srcAtoms) {
			val dstHeavy = atomMap.getAOrThrow(srcHeavy)
			dstAtoms.add(dstHeavy to h)
		}
	}

	return dstAtoms
}

private fun inferProtonationLeap(mol: Molecule, ffname: ForcefieldName): List<Pair<Atom, Atom>> {

	// run LEaP to add the hydrogens
	val pdb = mol.toPDB()
	val results = Leap.run(
		filesToWrite = mapOf("in.pdb" to pdb),
		commands = """
			|verbosity 2
			|source leaprc.${ffname.name}
			|mol = loadPDB in.pdb
			|addH mol
			|saveMol2 mol out.mol2 0
		""".trimMargin(),
		filesToRead = listOf("out.mol2")
	)

	val protonatedMol = Molecule.fromMol2(results.files["out.mol2"]
		?: throw Leap.Exception("LEaP didn't produce an output molecule", pdb, results))

	return protonatedMol.translateHydrogens(mol)
}

private fun inferProtonationAntechamberThenLeap(mol: Molecule, ffname: ForcefieldName): List<Pair<Atom, Atom>> {

	// run antechamber to infer all the atom and bond types
	val pdb = mol.toPDB()
	val antechamberResults = Antechamber.run(pdb, Antechamber.InType.Pdb, ffname.atomTypesOrThrow)
	val mol2 = antechamberResults.mol2
		?: throw Antechamber.Exception("Antechamber didn't produce an output molecule", pdb, antechamberResults)

	// run LEaP to add the hydrogens
	val results = Leap.run(
		filesToWrite = mapOf("in.mol2" to mol2),
		commands = """
			|verbosity 2
			|source leaprc.${ffname.name}
			|mol = loadMol2 in.mol2
			|addH mol
			|saveMol2 mol out.mol2 0
		""".trimMargin(),
		filesToRead = listOf("out.mol2")
	)

	val protonatedMol = Molecule.fromMol2(results.files["out.mol2"]
		?: throw Leap.Exception("LEaP didn't produce an output molecule", pdb, results))

	return protonatedMol.translateHydrogens(mol)
}

private fun Molecule.translateHydrogens(dst: Molecule): List<Pair<Atom,Atom>> {

	val src: Molecule = this

	val mapper = src.mapTo(dst)

	// map the heavy,hydrogen pairs from src to dst
	return src.atoms
		.filter { it.element == Element.Hydrogen }
		.mapNotNull { srcH ->

			// get the bonded heavy atom
			val srcHeavy = src.bonds
				.bondedAtoms(srcH)
				.firstOrNull { it.element != Element.Hydrogen }
				?: return@mapNotNull null

			val dstHeavy = mapper.mapAtom(srcHeavy) ?: return@mapNotNull null

			return@mapNotNull dstHeavy to srcH
		}
}


data class Protonation(
	val element: Element,
	val numHeavy: Int,
	val numH: Int,
	val hybridization: Hybridization
)

private data class ProtonationTypes(
	val heavy: String,
	val hydrogen: String,
	val heavyBond: String? = null,
	val bondedHeavy: Map<Element,String>? = emptyMap()
)

private val protonations = mapOf(

	// NOTE: not sure if all these hybridizations are correct
	// they really only matter for Nitrogen though, when multiple protonations have the same number of hydrogen atoms
	// and the only way to distinguish them is with different hybridizations

	Protonation(Element.Carbon, 0, 2, Hybridization.Sp2) to ProtonationTypes("c1", "hc"), // eg methylene
	Protonation(Element.Carbon, 0, 3, Hybridization.Sp2) to ProtonationTypes("c2", "hc"), // eg methyl cation
	Protonation(Element.Carbon, 0, 4, Hybridization.Sp3) to ProtonationTypes("c3", "hc"), // eg methane
	Protonation(Element.Carbon, 1, 1, Hybridization.Sp) to ProtonationTypes("c1", "hc",
		"T", // triple bond
		mapOf(
			Element.Carbon to "c1", // eg ethyne
			Element.Nitrogen to "n1", // TODO hydrogen cyanide?
			Element.Phosphorus to "p2" // TODO methylidynephosphane?
		)
	),
	Protonation(Element.Carbon, 1, 2, Hybridization.Sp2) to ProtonationTypes("c2", "hc",
		"D", // double bond
		mapOf(
			Element.Carbon to "c2", // eg ethene
			Element.Nitrogen to "n2", // TODO ???
			Element.Phosphorus to "p3", // methylenephosphine
			Element.Oxygen to "o" // TODO ???
		)
	),
	Protonation(Element.Carbon, 1, 3, Hybridization.Sp3) to ProtonationTypes("c3", "hc"), // eg ethane
	Protonation(Element.Carbon, 2, 1, Hybridization.Sp2) to ProtonationTypes("c2", "hc"), // eg benzene
	Protonation(Element.Carbon, 2, 2, Hybridization.Sp3) to ProtonationTypes("c3", "hc"), // eg cyclohexane
	Protonation(Element.Carbon, 3, 1, Hybridization.Sp3) to ProtonationTypes("c3", "hc"), // eg isobutane

	Protonation(Element.Nitrogen, 0, 2, Hybridization.Sp2) to ProtonationTypes("n2", "hn"), // eg azanide anion
	Protonation(Element.Nitrogen, 0, 3, Hybridization.Sp3) to ProtonationTypes("n3", "hn"), // eg ammonia
	Protonation(Element.Nitrogen, 0, 4, Hybridization.Sp3) to ProtonationTypes("n4", "hn"), // eg ammonium cation
	Protonation(Element.Nitrogen, 1, 1, Hybridization.Sp) to ProtonationTypes("n1", "hn",
		"T", // triple bond
		mapOf(
			Element.Carbon to "c1", // TODO ???
			Element.Nitrogen to "n1", // eg diazynediium
			Element.Phosphorus to "p2" // TODO ???
		)
	),
	Protonation(Element.Nitrogen, 1, 1, Hybridization.Sp2) to ProtonationTypes("n2", "hn"), // eg diazene
	Protonation(Element.Nitrogen, 1, 2, Hybridization.Sp2) to ProtonationTypes("na", "hn"), // eg formamide
	Protonation(Element.Nitrogen, 1, 2, Hybridization.Sp3) to ProtonationTypes("n3", "hn"), // eg hydrazine
	Protonation(Element.Nitrogen, 1, 3, Hybridization.Sp3) to ProtonationTypes("n4", "hn"), // eg diazanediium
	Protonation(Element.Nitrogen, 2, 1, Hybridization.Sp2) to ProtonationTypes("na", "hn"), // eg N-methylformamide
	Protonation(Element.Nitrogen, 2, 1, Hybridization.Sp3) to ProtonationTypes("n3", "hn"), // eg dimethylamine
	Protonation(Element.Nitrogen, 2, 2, Hybridization.Sp3) to ProtonationTypes("n4", "hn"), // eg dimethylammonium cation
	Protonation(Element.Nitrogen, 3, 1, Hybridization.Sp3) to ProtonationTypes("n4", "hn"), // eg trimethylammonium

	Protonation(Element.Oxygen, 0, 1, Hybridization.Sp2) to ProtonationTypes("o", "hw"), // eg hydroxide anion
	Protonation(Element.Oxygen, 0, 2, Hybridization.Sp3) to ProtonationTypes("ow", "hw"), // eg water (oxidane)
	Protonation(Element.Oxygen, 0, 3, Hybridization.Sp3) to ProtonationTypes("ow", "hw"), // eg hydronium cation
	Protonation(Element.Oxygen, 1, 1, Hybridization.Sp3) to ProtonationTypes("oh", "ho"), // eg hydrogen peroxide
	Protonation(Element.Oxygen, 1, 2, Hybridization.Sp3) to ProtonationTypes("oh", "ho"), // eg methyloxonium cation

	Protonation(Element.Phosphorus, 0, 2, Hybridization.Sp2) to ProtonationTypes("p2", "hp"), // eg phosphanide anion
	Protonation(Element.Phosphorus, 0, 3, Hybridization.Sp3) to ProtonationTypes("p3", "hp"), // eg phosphine
	Protonation(Element.Phosphorus, 0, 4, Hybridization.Sp3) to ProtonationTypes("p5", "hp"), // eg phosphonium cation
	Protonation(Element.Phosphorus, 1, 1, Hybridization.Sp2) to ProtonationTypes("p2", "hp",
		"D", // double bond
		mapOf(
			Element.Carbon to "c2", // eg methylenephosphine
			Element.Nitrogen to "n2", // TODO ???
			Element.Phosphorus to "p3", // TODO diphosphene
			Element.Oxygen to "o" // TODO ???
		)
	),
	/* TODO: can't seem to get PH2+ to be planar here? can't find amber types that work ...
	      let's just not support this protonation for now
	Protonation(Element.Phosphorus, 1, 2, Hybridization.Sp2) to ProtonationTypes("p4", "hp",
		"D", // double bond
		mapOf(
			Element.Carbon to "c2", // eg methylenephosphonium cation
			Element.Nitrogen to "n2", // TODO ???
			Element.Phosphorus to "p3", // TODO ???
			Element.Oxygen to "o" // eg oxophosphonium cation
		)
	),
	*/
	Protonation(Element.Phosphorus, 1, 2, Hybridization.Sp3) to ProtonationTypes("p3", "hp"), // eg diphosphane
	Protonation(Element.Phosphorus, 1, 3, Hybridization.Sp3) to ProtonationTypes("p5", "hp"), // eg methylphosphonium cation
	// TODO: Protonation(Element.Phosphorus, 2, 1, Hybridization.Sp2) ???
	Protonation(Element.Phosphorus, 2, 1, Hybridization.Sp3) to ProtonationTypes("p3", "hp"), // eg dimethylphosphine
	Protonation(Element.Phosphorus, 2, 2, Hybridization.Sp3) to ProtonationTypes("p5", "hp"), // eg dimethylphosphonium
	Protonation(Element.Phosphorus, 3, 1, Hybridization.Sp3) to ProtonationTypes("p5", "hp"), // eg trimethylphosphonium

	Protonation(Element.Sulfur, 0, 1, Hybridization.Sp2) to ProtonationTypes("s", "hs"), // eg bisulfide anion
	Protonation(Element.Sulfur, 0, 2, Hybridization.Sp3) to ProtonationTypes("sh", "hs"), // eg hydrogen sulfide
	Protonation(Element.Sulfur, 0, 3, Hybridization.Sp3) to ProtonationTypes("sh", "hs"), // eg sulfonium cation
	Protonation(Element.Sulfur, 1, 1, Hybridization.Sp3) to ProtonationTypes("sh", "hs"), // eg hydrogen disulfide
	Protonation(Element.Sulfur, 1, 2, Hybridization.Sp3) to ProtonationTypes("sh", "hs") // eg methylsulfonium cation
	// don't think any of the positive oxidation states of sulfur can be protonated, right?
	// wouldn't highly electronegative ligands (eg Oxygen) steal all the protons (ie tautomerization)?
)

enum class Hybridization {
	Sp,
	Sp2,
	Sp3
}
