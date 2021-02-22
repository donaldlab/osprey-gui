package edu.duke.cs.ospreygui.io

import edu.duke.cs.ospreygui.compiler.ConfSpaceCompiler
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.prep.ConfSpace
import java.nio.file.Paths


fun main() {

	val path = Paths.get("redacted")
	val confSpace = ConfSpace.fromToml(path.read())

	val netChargesByMolName = mapOf(
		"ANP" to -1
	)

	// compile it
	withService {

		ConfSpaceCompiler(confSpace).run {

			// use default setings
			forcefields.add(Forcefield.Amber96)
			forcefields.add(Forcefield.EEF1)

			// add necessary net charges
			netCharges[confSpace.findMol("ANP")]?.netCharge = -1

			println("compiling ...")
			val report = compile().run {
				printUntilFinish(5000)
				report!!
			}

			// if there was an error, throw it
			report.error?.let { throw Error("can't compile", it) }
		}
	}
}
