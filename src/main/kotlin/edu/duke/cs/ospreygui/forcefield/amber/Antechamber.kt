package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.io.*
import java.io.IOException
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

	enum class AtomTypes(val id: String) {
		Gaff("gaff"),
		Gaff2("gaff2"),
		Amber("amber"),
		BCC("bcc"),
		SYBYL("sybyl")
	}

	fun run(pdb: String, atomTypes: AtomTypes = AtomTypes.SYBYL): Results {

		// make sure antechamber is available for this platform
		if (!antechamberPath.exists) {
			throw UnsupportedOperationException("Antechamber is not yet available for ${Platform.get()}")
		}

		tempFolder("antechamber") { cwd ->

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
					"-at", atomTypes.id
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

	class Exception(val msg: String, val pdb: String, val results: Results) : RuntimeException(StringBuilder().apply {
		append(msg)
		append("\n\n")
		append("PDB:\n")
		append(pdb)
		append("\n\n")
		append("console:\n")
		results.console.forEach {
			append(it)
			append("\n")
		}
	}.toString())
}
