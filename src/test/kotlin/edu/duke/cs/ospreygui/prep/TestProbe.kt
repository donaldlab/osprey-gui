package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.OspreyService
import edu.duke.cs.ospreygui.io.fromPDB
import edu.duke.cs.ospreygui.io.toPDB
import edu.duke.cs.ospreygui.io.withService
import edu.duke.cs.ospreyservice.services.ClashesRequest
import io.kotlintest.shouldBe


class TestProbe : SharedSpec({

	group("1cc8") {

		test("protein") {
			withService {

				val mol = Molecule.fromPDB(OspreyGui.getResourceAsString("1cc8.pdb"))
				val results = OspreyService.clashes(ClashesRequest(mol.toPDB()))

				results.groups.getValue("small overlap").run {
					vectors.getValue("orange").size shouldBe 115
					vectors.getValue("red").size shouldBe 32
					vectors.getValue("yellow").size shouldBe 311
					vectors.getValue("yellowtint").size shouldBe 833
				}

				results.groups.getValue("bad overlap").run {
					vectors.getValue("hotpink").size shouldBe 10
				}

				results.groups.getValue("vdw contact").run {
					dots.getValue("sky").size shouldBe 3506
					dots.getValue("green").size shouldBe 2862
					dots.getValue("blue").size shouldBe 4028
					dots.getValue("sea").size shouldBe 2818
				}

				// no H atoms yet, so no Hbonds or salt bridges
			}
		}
	}
})
