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
			addForcefield(Forcefield.Amber96)
			addForcefield(Forcefield.EEF1)

			// TODO: simplify this?
			//   eg  netCharges[confSpace.getMol("ANP")] = 5
			//   or better yet, bake the net charge into the mol definiton somehow?
			// add necessary net charges
			for ((type, mol) in confSpace.mols) {
				netCharges[mol, type]?.netCharge = netChargesByMolName.getValue(mol.name)
			}

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
