package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.fromPDB
import io.kotlintest.matchers.maps.shouldNotContainKey
import io.kotlintest.shouldBe
import org.joml.Vector3d


class TestPolymerPartitioning : SharedSpec({

	context("1CC8") {

		val mol = Molecule.fromPDB(OspreyGui.getResourceAsString("1cc8.pdb")) as Polymer

		test("partition") {

			val partition = mol
				.partition()
				.groupBy(
					keySelector = { (type, _) -> type },
					valueTransform = { (_, mol) -> mol }
				)

			// check the molecule type counts
			partition[MoleculeType.Protein]!!.size shouldBe 1
			partition[MoleculeType.SmallMolecule]!!.size shouldBe 3
			partition[MoleculeType.Solvent]!!.size shouldBe 117
			partition shouldNotContainKey MoleculeType.DNA
			partition shouldNotContainKey MoleculeType.RNA
			partition shouldNotContainKey MoleculeType.Ion
			partition shouldNotContainKey MoleculeType.Synthetic

			// check the protein
			partition[MoleculeType.Protein]!!.first().apply {

				this as Polymer

				chains.size shouldBe 1
				chains[0].apply {
					id shouldBe "A"
					residues.size shouldBe 72

					// TODO: check a few resides?
				}

				atoms.size shouldBe 567
			}

			partition[MoleculeType.SmallMolecule]!!.apply {

				// check the mercury atom
				find { it.type == "HG" }!!.apply {
					atoms.size shouldBe 1
					atoms[0].apply {
						name shouldBe "HG"
						element shouldBe Element.Mercury
					}
				}

				// check one of the benzamidines
				find { it.type == "BEN" }!!.apply {

					atoms.size shouldBe 9

					// spot check a couple atoms
					atoms.find { it.name == "C1" }!!.apply {
						element shouldBe Element.Carbon
						pos shouldBe Vector3d(6.778, 10.510, 20.665)
					}
					atoms.find { it.name == "N2" }!!.apply {
						element shouldBe Element.Nitrogen
						pos shouldBe Vector3d(4.965, 11.590, 21.821)
					}
				}
			}
		}

		test("combine protein, Hg, benzamidines") {

			mol.partition()
				.filter { (type, _) -> type in setOf(MoleculeType.Protein, MoleculeType.SmallMolecule) }
				.map { (_, mol) -> mol }
				.combine("1CC8")
				.apply {

					// the protein gets its own chain,
					// and all the small molecules get combined into another chain
					chains.size shouldBe 2

					// check the protein
					chains.find { it.id == "A" }!!.apply {

						residues.size shouldBe 72
					}

					// check the small molecules
					chains.find { it.id == "B" }!!.apply {

						residues.size shouldBe 3

						// check the mercury atom
						residues.find { it.type == "HG" }!!.apply {
							atoms.size shouldBe 1
							atoms[0].apply {
								name shouldBe "HG"
								element shouldBe Element.Mercury
							}
						}

						// check one of the benzamidines
						residues.find { it.type == "BEN" }!!.apply {

							atoms.size shouldBe 9

							// spot check a couple atoms
							atoms.find { it.name == "C1" }!!.apply {
								element shouldBe Element.Carbon
								pos shouldBe Vector3d(6.778, 10.510, 20.665)
							}
							atoms.find { it.name == "N2" }!!.apply {
								element shouldBe Element.Nitrogen
								pos shouldBe Vector3d(4.965, 11.590, 21.821)
							}
						}
					}

					atoms.size shouldBe 567 + 1 + 9 + 9
				}
		}
	}
})
