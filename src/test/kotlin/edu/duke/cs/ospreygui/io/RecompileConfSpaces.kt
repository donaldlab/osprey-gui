package edu.duke.cs.ospreygui.io

import edu.duke.cs.osprey.tools.LZMA2
import edu.duke.cs.ospreygui.compiler.ConfSpaceCompiler
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.prep.ConfSpace
import java.nio.file.Files
import java.nio.file.Paths


/**
 * Just a simple tool to easily recompile all the conf spaces
 * in Osprey's test suite to the new version of the compiled conf space format.
 */
fun main() {

	val extension = ".confspace.toml"
	val dir = Paths.get("../osprey3/test-resources/confSpaces")

	Files.list(dir)
		.filter { it.fileName.toString().endsWith(extension) }
		.forEach { inPath ->

			val filename = inPath.fileName.toString()
			val basename = filename.substring(0, filename.length - extension.length)

			// load the conf space
			println("loading $basename ...")
			val confSpace = ConfSpace.fromToml(inPath.read())

			// compile it
			ConfSpaceCompiler(confSpace).run {

				// use default setings
				addForcefield(Forcefield.Amber96)
				addForcefield(Forcefield.EEF1)

				// TODO: how to input net charges?

				println("compiling $basename ...")
				val report = compile().run {
					printUntilFinish(5000)
					report!!
				}

				// if there was an error, throw it
				report.error?.let { throw Error("can't compile $basename", it) }

				// otherwise, yay it worked!
				val compiledConfSpace = report.compiled!!

				// save the compressed conf space
				val outPath = inPath.parent.resolve("$basename.ccs.xz")
				println("saving $basename ...")
				LZMA2.compress(compiledConfSpace.toBytes()).write(outPath)
			}
		}
}
