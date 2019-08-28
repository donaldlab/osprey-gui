package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.io.*
import java.io.IOException
import java.nio.file.Paths


object Parmchk {

	private val homePath = Paths.get("ambertools").toAbsolutePath()
	private val parmchkPath = homePath.resolve("bin/parmchk2").toAbsolutePath()

	data class Results(
		val errorCode: Int,
		val console: List<String>,
		val frcmod: String?
	)

	enum class AtomTypes(val id: String) {

		Gaff("gaff"),
		Gaff2("gaff2");

		companion object {

			fun from(ffname: ForcefieldName) =
				when (ffname.atomTypes) {
					Antechamber.AtomTypes.Gaff -> Gaff
					Antechamber.AtomTypes.Gaff2 -> Gaff2
					else -> null
				}

			fun fromOrThrow(ffname: ForcefieldName) =
				from(ffname) ?: throw IllegalArgumentException("forcefield $ffname is not supported by Parmchk")
		}
	}

	fun run(mol2: String, atomTypes: AtomTypes): Results {

		// make sure parmchk is available for this platform
		if (!parmchkPath.exists) {
			throw UnsupportedOperationException("Parmchk is not yet available for ${Platform.get()}")
		}

		tempFolder("parmchk") { cwd ->

			// write the input files
			mol2.write(cwd.resolve("mol.mol2"))

			// start parmchk
			val process = ProcessBuilder()
				.command(
					parmchkPath.toString(),
					"-i", "mol.mol2",
					"-f", "mol2",
					"-s", atomTypes.id,
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
}
