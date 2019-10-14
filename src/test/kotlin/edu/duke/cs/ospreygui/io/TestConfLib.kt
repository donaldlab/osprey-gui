package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import io.kotlintest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotlintest.matchers.collections.shouldExist
import io.kotlintest.matchers.types.shouldBeTypeOf
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

			atoms.size shouldBe 6
			val ha = atoms.find { it.name == "HA" }!!.apply {
				element shouldBe Element.Hydrogen
			}
			val hb1 = atoms.find { it.name == "HB1" }!!.apply {
				element shouldBe Element.Hydrogen
			}
			val cb = atoms.find { it.name == "CB" }!!.apply {
				element shouldBe Element.Carbon
			}
			val h = atoms.find { it.name == "H" }!!.apply {
				element shouldBe Element.Hydrogen
			}

			bonds.size shouldBe 3
			bonds.shouldExist { it.a == hb1 && it.b == cb }

			anchors.size shouldBe 2
			val anchorCA = (anchors.find { it.id == 1 } as ConfLib.Anchor.Single).apply {
				bonds.shouldContainExactlyInAnyOrder(ha, cb)
			}
			val anchorN = (anchors.find { it.id == 2 } as ConfLib.Anchor.Single).apply {
				bonds.shouldContainExactlyInAnyOrder(h)
			}

			confs.size shouldBe 1
			confs.getValue("ALA").run {

				coords.size shouldBe 6
				coords[cb] shouldBe Vector3d(19.617, 5.198, 24.407)

				anchorCoords.size shouldBe 2
				anchorCoords.getValue(anchorCA).shouldBeTypeOf<ConfLib.AnchorCoords.Single> {
					it.b shouldBe Vector3d(20.267, 6.768, 22.647)
				}
				anchorCoords.getValue(anchorN).shouldBeTypeOf<ConfLib.AnchorCoords.Single> {
					it.c shouldBe Vector3d(10.668, 14.875, 20.325)
				}
			}
		}

		conflib.fragments.getValue("VAL").run {

			id shouldBe "VAL"
			name shouldBe "Valine"

			atoms.size shouldBe 12
			bonds.size shouldBe 9
			anchors.size shouldBe 2

			confs.size shouldBe 3
			confs.getValue("p").run {
				name shouldBe "p"
				coords.size shouldBe 12
				coords[atoms.find { it.name == "HA" }] shouldBe Vector3d(108.613, 17.002, -4.136)
				coords[atoms.find { it.name == "CG2" }] shouldBe Vector3d(106.309809, 15.583253, -4.049056)
			}
		}

		conflib.fragments.getValue("PRO").run {

			id shouldBe "PRO"
			name shouldBe "Proline"

			atoms.size shouldBe 10
			bonds.size shouldBe 8

			anchors.size shouldBe 1
			val anchor = (anchors.find { it.id == 1 } as ConfLib.Anchor.Double).apply {
				bondsa.shouldContainExactlyInAnyOrder(
					atoms.find { it.name == "CB" },
					atoms.find { it.name == "HA" }
				)
				bondsb.shouldContainExactlyInAnyOrder(
					atoms.find { it.name == "CD" }
				)
			}

			confs.size shouldBe 2
			confs.getValue("up").run {

				coords.size shouldBe 10
				coords[atoms.find { it.name == "HG3" }] shouldBe Vector3d(21.152899, 20.472575, 35.499174)

				anchorCoords.getValue(anchor).shouldBeTypeOf<ConfLib.AnchorCoords.Double> {
					it.d shouldBe Vector3d(21.102527, 24.472094, 37.053483)
				}
			}
		}
	}
})