package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.io.*
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths


object Leap {

	private val leapPath = Paths.get("ambertools/bin/teLeap").toAbsolutePath()
	private val datPath = Paths.get("ambertools/dat/leap").toAbsolutePath()

	data class Results(
		val exitCode: Int,
		val console: List<String>,
		val files: Map<String,String?>
	)

	fun run(cwd: Path, filesToWrite: Map<String,String>, commands: String, filesToRead: List<String> = emptyList()): Results {

		// make sure leap is available for this platform
		if (!leapPath.exists) {
			throw UnsupportedOperationException("LEaP is not yet available for ${Platform.get()}")
		}

		// write the files
		for ((filename, content) in filesToWrite) {
			content.write(cwd.resolve(filename))
		}

		// write the leap commands
		val commandsPath = cwd.resolve("commands")
		commands.write(commandsPath)

		// start leap
		val process = ProcessBuilder()
			.command(
				leapPath.toString(),
				"-I", datPath.resolve("prep").toString(),
				"-I", datPath.resolve("prep/oldff").toString(),
				"-I", datPath.resolve("lib").toString(),
				"-I", datPath.resolve("lib/oldff").toString(),
				"-I", datPath.resolve("parm").toString(),
				"-I", datPath.resolve("cmd").toString(),
				"-I", datPath.resolve("cmd/oldff").toString(),
				"-f", commandsPath.toAbsolutePath().toString()
			)
			.directory(cwd.toFile())
			.stream()
			.waitFor()

		// return the results
		return Results(
			process.exitCode,
			process.console.toList(),
			filesToRead.associateWith {
				try {
					cwd.resolve(it).read()
				} catch (ex: IOException) {
					null
				}
			}
		)
	}
}
