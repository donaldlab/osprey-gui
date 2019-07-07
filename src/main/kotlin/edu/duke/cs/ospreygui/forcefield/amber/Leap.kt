package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.ospreygui.io.*
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths


object Leap {

	// TODO: detect this for other os/arch combos?
	private val platform = "linux/amd64"

	private val leapPath = Paths.get("bin/$platform/teLeap").toAbsolutePath()
	private val datPath = Paths.get("bin/dat/leap").toAbsolutePath()

	data class Results(
		val exitCode: Int,
		val stdout: List<String>,
		val stderr: List<String>,
		val files: Map<String,String?>
	)

	fun run(cwd: Path, filesToWrite: Map<String,String>, commands: String, filesToRead: List<String> = emptyList()): Results {

		// make sure leap is available for this platform?
		if (!leapPath.exists) {
			throw UnsupportedOperationException("LEaP is not yet available for $platform")
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
			.start()

		// wait for it to finish
		val streamer = process.stream().waitFor()

		// return the results
		return Results(
			process.exitValue(),
			streamer.stdout.toList(),
			streamer.stderr.toList(),
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
