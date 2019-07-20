package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.fromPDB
import edu.duke.cs.ospreygui.io.toPDB
import io.kotlintest.shouldBe


class TestLeap : SharedSpec({

	fun String.skipFirstLine() = substring(indexOf('\n') + 1)

	context("1CC8") {

		// load our favorite testing protein structure
		val mol = Molecule.fromPDB(OspreyGui.getResourceAsString("1cc8.pdb")) as Polymer

		test("protein") {

			// filter out the small molecules and solvents
			val molFiltered = mol.partition()
				.filter { (type, _) -> type in setOf(MoleculeType.Protein) }
				.map { (_, mol) -> mol }
				.combine("1CC8")

			// run LEaP
			val results = Leap.run(
				filesToWrite = mapOf(
					"mol.pdb" to molFiltered.toPDB()
				),
				commands = """
					|source leaprc.ff96
					|mol = loadPdb mol.pdb
					|saveamberparm mol mol.top mol.crd
				""".trimMargin(),
				filesToRead = listOf("mol.top", "mol.crd")
			)

			// make sure the results still match what we expect
			// (skip the first line of the topology file, since it has a timestamp)
			results.files["mol.top"]?.skipFirstLine() shouldBe OspreyGui.getResourceAsString("1cc8.top").skipFirstLine()
			results.files["mol.crd"] shouldBe OspreyGui.getResourceAsString("1cc8.crd")
		}
	}
})
