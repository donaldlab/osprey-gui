package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.io.exists
import edu.duke.cs.ospreygui.io.read
import edu.duke.cs.ospreygui.io.stream
import edu.duke.cs.ospreygui.io.write
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths


object Antechamber {

	private val homePath = Paths.get("ambertools").toAbsolutePath()
	private val antechamberPath = homePath.resolve("bin/antechamber").toAbsolutePath()

	// NOTE: antechamber calls out to the following programs:
	// sqm (or mopac or divcon), atomtype, am1bcc, bondtype, espgen, respgen, prepgen

	data class Results(
		val exitCode: Int,
		val console: List<String>,
		val mol2: String?
	)

	fun run(cwd: Path, pdb: String): Results {

		// make sure antechamber is available for this platform
		if (!antechamberPath.exists) {
			throw UnsupportedOperationException("Antechamber is not yet available for ${Platform.get()}")
		}

		// write the input files
		pdb.write(cwd.resolve("mol.pdb"))

		// start antechamber
		val process = ProcessBuilder()
			.command(
				antechamberPath.toString(),
				"-i", "mol.pdb",
				"-fi", "pdb",
				"-o", "mol.mol2",
				"-fo", "mol2",
				"-dr", "n" // turn off "acdoctor" mode
			)
			.apply {
				environment().apply {
					put("AMBERHOME", homePath.toString())
				}
			}
			.directory(cwd.toFile())
			.stream()
			.waitFor()

		// return the results
		return Results(
			process.exitCode,
			process.console.toList(),
			try {
				cwd.resolve("mol.mol2").read()
			} catch (ex: IOException) {
				null
			}
		)
	}

}
