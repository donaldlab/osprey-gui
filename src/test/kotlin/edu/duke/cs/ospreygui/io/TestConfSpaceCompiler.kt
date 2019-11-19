package edu.duke.cs.ospreygui.io

import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import io.kotlintest.shouldBe
import java.nio.file.Paths


class TestConfSpaceCompiler : SharedSpec({

	test("Amber96 + EEF1") {

		ConfSpaceCompiler(makeTestConfSpace()).apply {

			// add forcefields
			addForcefield(Forcefield.Amber96)
			addForcefield(Forcefield.EEF1)

			// configure net charges for benzamidine
			val ben = confSpace.mols
				.find { (type, _) -> type == MoleculeType.SmallMolecule }!!
				.let { (_, mol) -> mol }
			val benPos = confSpace.designPositionsByMol[ben]!!.first()
			val benConfSpace = confSpace.positionConfSpaces[benPos]!!
			smallMolNetCharges[ben] = NetCharges(ben).apply {

				netCharge = -1

				this[benPos, benConfSpace.wildTypeFragment!!] = -1
				this[benPos, benConfSpace.confs.keys.find { it.id == "bar" }!!] = -1
			}

			// copmile it!
			compile().run {

				// TEMP
				errors.forEach {
					println(it)
					it.extraInfo?.let { println("ExtraInfo:\n${it}") }
				}
				toml?.write(Paths.get("test.ccs.toml"))

				// no errors
				errors.size shouldBe 0
			}
		}
	}
})
