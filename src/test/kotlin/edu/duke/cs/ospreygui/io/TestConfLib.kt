package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import org.joml.Vector3d


class TestConfLib : SharedSpec({

	test("read Lovell") {

		val conflib = ConfLib.from(OspreyGui.getResourceAsString("conflib/lovell.conflib.toml"))

		// spot check a few bits
		conflib.name shouldBe "Amino Acids: The Penultimate Rotamer Library (with Hydroxyl rotamers)"
		conflib.description shouldNotBe null
		conflib.citation shouldNotBe null

		conflib.fragments.getValue("ALA").run {

			id shouldBe "ALA"
			name shouldBe "Alanine"

			atoms.size shouldBe 5
			val ha = atoms.find { it.name == "HA" }!!.apply {
				element shouldBe Element.Hydrogen
			}
			val hb1 = atoms.find { it.name == "HB1" }!!.apply {
				element shouldBe Element.Hydrogen
			}
			val cb = atoms.find { it.name == "CB" }!!.apply {
				element shouldBe Element.Carbon
			}

			bonds.size shouldBe 3
			bonds.find { it.a == hb1 && it.b == cb } shouldNotBe null

			anchor.size shouldBe 3
			anchor.find { it.id == 1 }!!.apply {
				element shouldBe Element.Carbon
				pos shouldBe Vector3d(20.016, 5.363, 22.945)
			}

			anchorBonds.size shouldBe 2
			anchorBonds.find { it.atom == ha && it.anchor.id == 1 } shouldNotBe null

			confs.size shouldBe 1
		}

		conflib.fragments.getValue("VAL").run {

			id shouldBe "VAL"
			name shouldBe "Valine"

			atoms.size shouldBe 11
			bonds.size shouldBe 9
			anchor.size shouldBe 3
			anchorBonds.size shouldBe 2

			confs.size shouldBe 3
			confs.getValue("p").run {
				name shouldBe "p"
				coords.size shouldBe 11
				coords[atoms.find { it.name == "HA" }] shouldBe Vector3d(108.613000, 17.002000, -4.136000)
				coords[atoms.find { it.name == "CG2" }] shouldBe Vector3d(106.309809, 15.583253, -4.049056)
			}
		}
	}
})
