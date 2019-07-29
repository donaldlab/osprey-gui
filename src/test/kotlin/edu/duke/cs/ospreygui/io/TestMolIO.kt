package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.osprey.structure.OMOLIO
import edu.duke.cs.ospreygui.SharedSpec
import io.kotlintest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotlintest.shouldBe


class TestMolIO : SharedSpec({

	val dipeptide = Polymer("GLU-ILE Dipeptide").apply {

		val an = atoms.add(Atom(Element.Nitrogen, "N",   12.926, 26.240, 21.956))
		val ah = atoms.add(Atom(Element.Hydrogen, "H",   13.852, 26.300, 21.559))
		val aca = atoms.add(Atom(Element.Carbon,   "CA",  11.887, 26.268, 20.919))
		val aha = atoms.add(Atom(Element.Hydrogen, "HA",  10.976, 26.735, 21.302))
		val acb = atoms.add(Atom(Element.Carbon,   "CB",  12.419, 27.131, 19.778))
		val a2hb = atoms.add(Atom(Element.Hydrogen, "2HB", 12.708, 28.110, 20.162))
		val a3hb = atoms.add(Atom(Element.Hydrogen, "3HB", 13.313, 26.637, 19.397))
		val acg = atoms.add(Atom(Element.Carbon,   "CG",  11.406, 27.343, 18.646))
		val a2hg = atoms.add(Atom(Element.Hydrogen, "2HG", 10.922, 26.393, 18.399))
		val a3hg = atoms.add(Atom(Element.Hydrogen, "3HG", 10.622, 28.022, 18.999))
		val acd = atoms.add(Atom(Element.Carbon,   "CD",  12.088, 27.904, 17.388))
		val aoe1 = atoms.add(Atom(Element.Oxygen,   "OE1", 13.342, 27.880, 17.332))
		val aoe2 = atoms.add(Atom(Element.Oxygen,   "OE2", 11.353, 28.290, 16.460))
		val ac = atoms.add(Atom(Element.Carbon,   "C",   11.569, 24.847, 20.413))
		val ao = atoms.add(Atom(Element.Oxygen,   "O",   12.441, 24.182, 19.845))

		val bn = atoms.add(Atom(Element.Nitrogen, "N",   10.337, 24.382, 20.639))
		val bh = atoms.add(Atom(Element.Hydrogen, "H",    9.687, 24.994, 21.110))
		val bca = atoms.add(Atom(Element.Carbon,   "CA",   9.771, 23.183, 20.000))
		val bha = atoms.add(Atom(Element.Hydrogen, "HA",  10.555, 22.429, 19.908))
		val bcb = atoms.add(Atom(Element.Carbon,   "CB",   8.610, 22.575, 20.829))
		val bhb = atoms.add(Atom(Element.Hydrogen, "HB",   7.790, 23.295, 20.855))
		val bcg2 = atoms.add(Atom(Element.Carbon,   "CG2",  8.115, 21.280, 20.152))
		val b1hg2 = atoms.add(Atom(Element.Hydrogen, "1HG2", 7.230, 20.907, 20.662))
		val b2hg2 = atoms.add(Atom(Element.Hydrogen, "2HG2", 7.834, 21.470, 19.117))
		val b3hg2 = atoms.add(Atom(Element.Hydrogen, "3HG2", 8.890, 20.512, 20.180))
		val bcg1 = atoms.add(Atom(Element.Carbon,   "CG1",  9.037, 22.275, 22.287))
		val b2hg1 = atoms.add(Atom(Element.Hydrogen, "2HG1", 9.753, 21.453, 22.299))
		val b3hg1 = atoms.add(Atom(Element.Hydrogen, "3HG1", 9.527, 23.148, 22.714))
		val bcd1 = atoms.add(Atom(Element.Carbon,   "CD1",  7.864, 21.935, 23.216))
		val b1hd1 = atoms.add(Atom(Element.Hydrogen, "1HD1", 8.234, 21.813, 24.235))
		val b2hd1 = atoms.add(Atom(Element.Hydrogen, "2HD1", 7.128, 22.742, 23.201))
		val b3hd1 = atoms.add(Atom(Element.Hydrogen, "3HD1", 7.384, 21.006, 22.910))
		val bc = atoms.add(Atom(Element.Carbon,   "C",    9.313, 23.581, 18.589))
		val bo = atoms.add(Atom(Element.Oxygen,   "O",    8.222, 24.116, 18.417))

		bonds.add(an, ah)
		bonds.add(an, aca)
		bonds.add(aca, aha)
		bonds.add(aca, acb)
		bonds.add(acb, a2hb)
		bonds.add(acb, a3hb)
		bonds.add(acb, acg)
		bonds.add(acg, a2hg)
		bonds.add(acg, a3hg)
		bonds.add(acg, acd)
		bonds.add(acd, aoe1)
		bonds.add(acd, aoe2)
		bonds.add(aca, ac)
		bonds.add(ac, ao)

		bonds.add(ac, bn)

		bonds.add(bn, bh)
		bonds.add(bn, bca)
		bonds.add(bca, bha)
		bonds.add(bca, bcb)
		bonds.add(bcb, bhb)
		bonds.add(bcb, bcg2)
		bonds.add(bcg2, b1hg2)
		bonds.add(bcg2, b2hg2)
		bonds.add(bcg2, b3hg2)
		bonds.add(bcb, bcg1)
		bonds.add(bcg1, b2hg1)
		bonds.add(bcg1, b3hg1)
		bonds.add(bcg1, bcd1)
		bonds.add(bcd1, b1hd1)
		bonds.add(bcd1, b2hd1)
		bonds.add(bcd1, b3hd1)
		bonds.add(bca, bc)
		bonds.add(bc, bo)

		chains.add(Polymer.Chain("A").apply {
			residues.add(Polymer.Residue(
				"1",
				"GLU",
				listOf(an, ah, aca, aha, acb, a2hb, a3hb, acg, a2hg, a3hg, acd, aoe1, aoe2, ac, ao)
			))
			residues.add(Polymer.Residue(
				"2",
				"ILE",
				listOf(bn, bh, bca, bha, bcb, bhb, bcg2, b1hg2, b2hg2, b3hg2, bcg1, b2hg1, b3hg1, bcd1, b1hd1, b2hd1, b3hd1, bc, bo)
			))
		})
	}

	val benzamidine = Molecule("Benzamidine", type = "BEN").apply {

		val c1 = atoms.add(Atom(Element.Carbon, "C1", 6.778, 10.510, 20.665))
		val c2 = atoms.add(Atom(Element.Carbon, "C2", 5.994, 9.710, 19.842))
		val c3 = atoms.add(Atom(Element.Carbon, "C3", 6.562, 9.055, 18.751))
		val c4 = atoms.add(Atom(Element.Carbon, "C4", 7.916, 9.259, 18.444))
		val c5 = atoms.add(Atom(Element.Carbon, "C5", 8.711, 10.120, 19.210))
		val c6 = atoms.add(Atom(Element.Carbon, "C6", 8.128, 10.734, 20.335))
		val c = atoms.add(Atom(Element.Carbon, "C", 6.244, 11.152, 21.851))
		val n1 = atoms.add(Atom(Element.Nitrogen, "N1", 7.014, 11.257, 22.910))
		val n2 = atoms.add(Atom(Element.Nitrogen, "N2", 4.965, 11.590, 21.821))

		bonds.add(c1, c2)
		bonds.add(c1, c6)
		bonds.add(c1, c)
		bonds.add(c2, c3)
		bonds.add(c3, c4)
		bonds.add(c4, c5)
		bonds.add(c5, c6)
		bonds.add(c, n1)
		bonds.add(c, n2)
	}

	data class AtomData(
		val name: String,
		val elem: Element,
		val x: Double,
		val y: Double,
		val z: Double
	)
	fun Atom.toData() = AtomData(name, element, pos.x, pos.y, pos.z)
	fun Collection<Atom>.toData() = map { it.toData() }

	fun Molecule.Atoms.find(data: AtomData): Atom? =
		find { atom -> atom.toData() == data }

	infix fun Molecule.shouldBe(expected: Molecule) {

		val observed = this

		// make sure the two molecules are the same
		observed.name shouldBe expected.name
		observed.type shouldBe expected.type

		// check the atoms
		observed.atoms.toData() shouldContainExactlyInAnyOrder expected.atoms.toData()

		// check the bonds
		for (atom1 in expected.atoms) {
			val atom2 = observed.atoms.find(atom1.toData())!!
			observed.bonds.bondedAtoms(atom2).toData() shouldContainExactlyInAnyOrder expected.bonds.bondedAtoms(atom1).toData()
		}

		// check the polymer if needed
		(observed is Polymer) shouldBe (expected is Polymer)
		if (observed is Polymer && expected is Polymer) {

			observed.chains.map { it.id } shouldContainExactlyInAnyOrder expected.chains.map { it.id }
			for (chain1 in expected.chains) {
				val chain2 = observed.chains.find { it.id == chain1.id }!!
				chain2.residues.map { it.id } shouldContainExactlyInAnyOrder chain1.residues.map { it.id }
				for (res1 in chain1.residues) {
					val res2 = chain2.residues.find { it.id == res1.id }!!

					res2.type shouldBe res1.type
					res2.atoms.toData() shouldContainExactlyInAnyOrder res1.atoms.toData()
				}
			}
		}
	}

	group("OMOL roundtrip") {

		fun roundtrip(mol: Molecule) {
			Molecule.fromOMOL(mol.toOMOL()) shouldBe mol
		}

		test("dipeptide") {
			roundtrip(dipeptide)
		}
		test("benzamidine") {
			roundtrip(benzamidine)
		}
	}

	group("OSPREY roundtrip") {

		fun roundtrip(mol: Molecule) {
			mol.toOspreyMol().toMolecule() shouldBe mol
		}

		test("dipeptide") {
			roundtrip(dipeptide)
		}
		test("benzamidine") {
			roundtrip(benzamidine)
		}
	}

	group("OSPREY OMOL roundtrip") {

		fun roundtrip(mol: Molecule) {
			OMOLIO.read(OMOLIO.write(mol.toOspreyMol())).toMolecule() shouldBe mol
		}

		test("dipeptide") {
			roundtrip(dipeptide)
		}
		test("benzamidine") {
			roundtrip(benzamidine)
		}
	}

	group("Mol2 roundtrip") {

		fun roundtrip(mol: Molecule) {
			// NOTE: the Mol2 format doesn't have a place to put the molecule type,
			// so just explicitly pass it along to pass this test
			Molecule.fromMol2(mol.toMol2()) shouldBe mol
		}

		test("dipeptide") {
			roundtrip(dipeptide)
		}
		test("benzamidine") {
			roundtrip(benzamidine)
		}
	}
})
