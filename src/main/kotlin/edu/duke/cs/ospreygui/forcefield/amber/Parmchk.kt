package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.io.exists
import edu.duke.cs.ospreygui.io.read
import edu.duke.cs.ospreygui.io.stream
import edu.duke.cs.ospreygui.io.write
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths


object Parmchk {

	private val homePath = Paths.get("ambertools").toAbsolutePath()
	private val parmchkPath = homePath.resolve("bin/parmchk2").toAbsolutePath()

	data class Results(
		val errorCode: Int,
		val console: List<String>,
		val frcmod: String?
	)

	fun run(cwd: Path, mol2: String): Results {

		// make sure parmchk is available for this platform
		if (!parmchkPath.exists) {
			throw UnsupportedOperationException("Parmchk is not yet available for ${Platform.get()}")
		}

		// write the input files
		mol2.write(cwd.resolve("mol.mol2"))

		// start parmchk
		val process = ProcessBuilder()
			.command(
				parmchkPath.toString(),
				"-i", "mol.mol2",
				"-f", "mol2",
				"-o", "frcmod"
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
				cwd.resolve("frcmod").read()
			} catch (ex: IOException) {
				null
			}
		)
	}
}
