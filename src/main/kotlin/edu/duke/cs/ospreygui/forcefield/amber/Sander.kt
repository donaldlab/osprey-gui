package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.io.*
import org.joml.Vector3d
import java.nio.file.Paths


object Sander {

	private val sanderPath = Paths.get("ambertools/bin/sander").toAbsolutePath()

	// we customized sander to give us bigger limits here
	const val maxCommandsLineSize = 65664
	const val maxRestraintMaskSize = 65536

	data class Results(
		val exitCode: Int,
		val console: List<String>,
		val coords: List<Vector3d>
	)

	fun run(
		top: String,
		crd: String,
		commands: String
	): Results {

		// make sure sander is available for this platform
		if (!sanderPath.exists) {
			throw UnsupportedOperationException("Sander is not yet available for ${Platform.get()}")
		}

		tempFolder("sander") { cwd ->

			// inputs:
			// mdin input control data for the min/md run
			// mdout output user readable state info and diagnostics
			//    or "-o stdout" will send output to stdout (to the terminal) instead of to a file.
			// mdinfo output latest mdout-format energy info
			//    (is this really required?)
			// prmtop input molecular topology, force field, periodic box type, atom and residue names
			// inpcrd input initial coordinates and (optionally) velocities and periodic box size

			val topname = "mol.top"
			val crdname = "mol.crd"
			val cmdname = "mol.cmd"
			val outname = "mol.restrt"

			// write the input files
			top.write(cwd.resolve(topname))
			crd.write(cwd.resolve(crdname))

			// sander crashes if the commands don't end with a newline, so just add one to be safe
			"$commands\n".write(cwd.resolve(cmdname))

			// start leap
			val process = ProcessBuilder()
				.command(
					sanderPath.toString(),
					"-i", cmdname,
					"-o", "stdout",
					"-p", topname,
					"-c", crdname,
					"-r", outname,
					"-ref", crdname
				)
				.directory(cwd.toFile())
				.stream()
				.waitFor()

			// TODO: track progress info

			// parse the output coords
			val coords = cwd.resolve(outname)
				.takeIf { it.exists }
				?.read()
				?.let { text ->
					ArrayList<Vector3d>().apply {
						var lines = text.lines()

						// skip the first two lines
						lines = lines.subList(2, lines.size)

						for (line in lines) {

							// lines look like e.g.:
							//  14.7619439  27.0623578  24.0946254  13.9092238  25.8758637  24.2319541
							//   3.7678268  22.1883445   9.1170323

							fun String.toDoubleOrThrow() =
								toDoubleOrNull()
								?: throw IllegalArgumentException("$this doesn't appear to be a number\nin line\n$line")

							val parts = line
								.split(" ")
								.filter { it.isNotBlank() }

							if (parts.size >= 3) {
								add(Vector3d(
									parts[0].toDoubleOrThrow(),
									parts[1].toDoubleOrThrow(),
									parts[2].toDoubleOrThrow()
								))
							}
							if (parts.size >= 6) {
								add(Vector3d(
									parts[3].toDoubleOrThrow(),
									parts[4].toDoubleOrThrow(),
									parts[5].toDoubleOrThrow()
								))
							}
						}
					}
				}

			return Results(
				process.exitCode,
				process.console.toList(),
				coords ?: throw Exception("Sander didn't produce any output coordinates", process.console)
			)
		}
	}

	fun minimize(
		top: String,
		crd: String,
		numCycles: Int = 100,
		reportEveryCycles: Int = 10,
		restraintMask: String? = null,
		restraintWeight: Double = 1.0
	): Results {

		val commands = ArrayList<String>()

		commands += listOf(
			"imin=1", // do cartesian minimization
			"maxcyc=$numCycles",
			"ntpr=$reportEveryCycles",
			"ntxo=1" // format the output coords in ASCII
		)

		// TODO: expose more options?
		// ntmin     minimization type

		if (restraintMask != null) {

			// alas, the restraint mask has a limited size
			if (restraintMask.length > maxRestraintMaskSize) {
				throw IllegalArgumentException("Alas, the restraintmask for sander can only be $maxRestraintMaskSize characters. This is too long:\n$restraintMask")
			}

			commands += listOf(
				"ntr=1", // turn on cartesian restraints
				"restraint_wt=$restraintWeight",
				"restraintmask='$restraintMask'"
			)
		}

		commands += listOf(
			"igb=1" // use generalized borne solvation
		)
		// TODO: expose more solvation options?

		// check the command line sizes
		commands
			.filter { it.length > maxCommandsLineSize }
			.takeIf { it.isNotEmpty() }
			?.let { lines ->
				throw IllegalArgumentException("Sander commands lines size are over the limit of $maxCommandsLineSize:\n${lines.joinToString("\n")}")
			}

		return run(
			top,
			crd,
			"""
				|Header
				|&cntrl
				|${commands.joinToString(",\n")}
				|/
			""".trimMargin()
		)
	}

	class Exception(val msg: String, val console: Collection<String>) : RuntimeException(StringBuilder().apply {
		append(msg)
		append("\n\n")
		append("console:\n")
		console.forEach {
			append(it)
			append("\n")
		}
	}.toString())
}
