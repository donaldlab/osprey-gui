package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.Atom.Companion.identitySetOf
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.io.fromOMOL
import edu.duke.cs.ospreygui.show
import io.kotlintest.matchers.beLessThanOrEqualTo
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.should
import io.kotlintest.shouldBe
import org.joml.Vector3d


class TestMutation : SharedSpec({

	fun Polymer.Residue.shouldHaveAtomNear(atomName: String, x: Double, y: Double, z: Double) {
		findAtomOrThrow(atomName).pos.distance(Vector3d(x, y, z)) should beLessThanOrEqualTo(1e-3)
	}

	/**
	 * Use this to visually inspect the mutation, to see if the atoms and bonds look correct.
	 */
	fun DesignPosition.show() {
		mol.show(focusAtom=anchorGroups.first().first().anchorAtoms.first(), wait=true)
	}

	/**
	 * Then use this to dump the atom coords to build the regression test.
	 */
	fun Polymer.Residue.dump() {
		for (atom in atoms) {
			println("res.shouldHaveAtomNear(\"%s\", %.6f, %.6f, %.6f)"
				.format(atom.name, atom.pos.x, atom.pos.y, atom.pos.z))
		}
	}

	group("1CC8") {

		val conflib = ConfLib.from(OspreyGui.getResourceAsString("conflib/lovell.conflib.toml"))
		val protein1cc8 = Molecule.fromOMOL(OspreyGui.getResourceAsString("1cc8.protein.omol.toml"))[0] as Polymer

		// pick gly 17 (aribtrarily) to use as a mutable position
		fun Polymer.gly17() =
			findChainOrThrow("A").findResidueOrThrow("17")

		// and grab the N-wards residue too
		fun Polymer.ser16() =
			findChainOrThrow("A").findResidueOrThrow("16")

		data class Instance(
			val res: Polymer.Residue,
			val pos: DesignPosition
		)
		fun instance(): Instance {

			// copy the molecule, so we don't destroy the original
			val mol = protein1cc8.copy()

			val ser16 = mol.ser16()
			val gly17 = mol.gly17()
			val pos = DesignPosition("pos1", mol).apply {

				anchorGroups.apply {

					add(mutableListOf(
						DesignPosition.Anchor.Single(
							attachedAtoms = identitySetOf(
								gly17.findAtomOrThrow("HA2"),
								gly17.findAtomOrThrow("HA3")
							),
							a = gly17.findAtomOrThrow("CA"),
							b = gly17.findAtomOrThrow("N"),
							c = gly17.findAtomOrThrow("C")
						),
						DesignPosition.Anchor.Single(
							attachedAtoms = identitySetOf(
								gly17.findAtomOrThrow("H")
							),
							a = gly17.findAtomOrThrow("N"),
							b = ser16.findAtomOrThrow("C"),
							c = gly17.findAtomOrThrow("CA")
						)
					))

					add(mutableListOf(
						DesignPosition.Anchor.Double(
							attachedAtoms = identitySetOf(
								gly17.findAtomOrThrow("H"),
								gly17.findAtomOrThrow("HA2"),
								gly17.findAtomOrThrow("HA3")
							),
							a = gly17.findAtomOrThrow("CA"),
							b = gly17.findAtomOrThrow("N"),
							c = ser16.findAtomOrThrow("C"),
							d = gly17.findAtomOrThrow("C")
						)
					))
				}
			}

			return Instance(gly17, pos)
		}

		fun DesignPosition.AnchorMatch.shouldHaveAnchors_CA_N() {

			// should have gotten the 2x single anchors
			pairs.size shouldBe 2

			val (posAnchorCA, fragAnchorCA) = pairs[0]
			posAnchorCA.shouldBeTypeOf<DesignPosition.Anchor.Single> {
				it.a.name shouldBe "CA"
			}
			fragAnchorCA.shouldBeTypeOf<ConfLib.Anchor.Single> {
				it.id shouldBe 1
			}

			val (posAnchorN, fragAnchorN) = pairs[1]
			posAnchorN.shouldBeTypeOf<DesignPosition.Anchor.Single> {
				it.a.name shouldBe "N"
			}
			fragAnchorN.shouldBeTypeOf<ConfLib.Anchor.Single> {
				it.id shouldBe 2
			}
		}

		fun DesignPosition.AnchorMatch.shouldHaveAnchors_CAN() {

			// should have gotten the 1x double anchor
			pairs.size shouldBe 1

			val (posAnchor, fragAnchor) = pairs[0]
			posAnchor.shouldBeTypeOf<DesignPosition.Anchor.Double> {
				it.a.name shouldBe "CA"
				it.b.name shouldBe "N"
			}
			fragAnchor.shouldBeTypeOf<ConfLib.Anchor.Double> {
				it.id shouldBe 1
			}
		}

		// just spot-check a few amino acids

		test("glycine") {

			val (res, pos) = instance()

			// find the glycine conformation in the library
			val frag = conflib.fragments.getValue("GLY")
			val conf = frag.confs.getValue("GLY")

			// check the anchors
			pos.findAnchorMatch(frag)!!.shouldHaveAnchors_CA_N()

			// do eeeet!
			pos.setConf(frag, conf)

			// does this look like glycine?
			res.atoms.size shouldBe 7
			res.shouldHaveAtomNear("N", 4.400000, -0.515000, 7.533000)
			res.shouldHaveAtomNear("CA", 5.791000, -0.751000, 7.871000)
			res.shouldHaveAtomNear("C", 6.672000, 0.451000, 7.612000)
			res.shouldHaveAtomNear("O", 7.716000, 0.674000, 8.236000)
			res.shouldHaveAtomNear("HA2", 5.865571, -1.028786, 8.932637)
			res.shouldHaveAtomNear("HA3", 6.167383, -1.604053, 7.287993)
			res.shouldHaveAtomNear("H", 3.958053, -0.848456, 6.691013)
		}

		test("valine") {

			val (res, pos) = instance()

			val frag = conflib.fragments.getValue("VAL")
			val conf = frag.confs.getValue("t")

			// check the anchors
			pos.findAnchorMatch(frag)!!.shouldHaveAnchors_CA_N()

			// do eeeet!
			pos.setConf(frag, conf)

			// does this look like valine?
			res.atoms.size shouldBe 16
			res.shouldHaveAtomNear("N", 4.400000, -0.515000, 7.533000)
			res.shouldHaveAtomNear("CA", 5.791000, -0.751000, 7.871000)
			res.shouldHaveAtomNear("C", 6.672000, 0.451000, 7.612000)
			res.shouldHaveAtomNear("O", 7.716000, 0.674000, 8.236000)
			res.shouldHaveAtomNear("HA", 5.843315, -0.997520, 8.942108)
			res.shouldHaveAtomNear("CB", 6.366537, -1.934244, 7.054276)
			res.shouldHaveAtomNear("HB", 6.229395, -1.728927, 5.982360)
			res.shouldHaveAtomNear("CG1", 7.853488, -2.084830, 7.332262)
			res.shouldHaveAtomNear("CG2", 5.625587, -3.217240, 7.393457)
			res.shouldHaveAtomNear("HG11", 8.253188, -2.927268, 6.748425)
			res.shouldHaveAtomNear("HG12", 8.375506, -1.160221, 7.046118)
			res.shouldHaveAtomNear("HG13", 8.009019, -2.276337, 8.404558)
			res.shouldHaveAtomNear("HG21", 6.043071, -4.048968, 6.807168)
			res.shouldHaveAtomNear("HG22", 5.737316, -3.432624, 8.466278)
			res.shouldHaveAtomNear("HG23", 4.558468, -3.099393, 7.152487)
			res.shouldHaveAtomNear("H", 3.958053, -0.848456, 6.691013)
		}

		test("tryptophan") {

			val (res, pos) = instance()

			val frag = conflib.fragments.getValue("TRP")
			val conf = frag.confs.getValue("t90")

			// check the anchors
			pos.findAnchorMatch(frag)!!.shouldHaveAnchors_CA_N()

			// do eeeet!
			pos.setConf(frag, conf)

			// does this look like tryptophan?
			res.atoms.size shouldBe 24
			res.shouldHaveAtomNear("N", 4.400000, -0.515000, 7.533000)
			res.shouldHaveAtomNear("CA", 5.791000, -0.751000, 7.871000)
			res.shouldHaveAtomNear("C", 6.672000, 0.451000, 7.612000)
			res.shouldHaveAtomNear("O", 7.716000, 0.674000, 8.236000)
			res.shouldHaveAtomNear("HA", 5.843864, -0.979317, 8.945833)
			res.shouldHaveAtomNear("CB", 6.346565, -1.946694, 7.093316)
			res.shouldHaveAtomNear("HB2", 5.705770, -2.821187, 7.279278)
			res.shouldHaveAtomNear("HB3", 6.288212, -1.728491, 6.016449)
			res.shouldHaveAtomNear("CG", 7.764233, -2.300739, 7.440858)
			res.shouldHaveAtomNear("CD1", 8.178192, -3.168377, 8.410530)
			res.shouldHaveAtomNear("CD2", 8.954448, -1.792557, 6.822732)
			res.shouldHaveAtomNear("HD1", 7.509462, -3.732152, 9.076669)
			res.shouldHaveAtomNear("NE1", 9.550764, -3.235475, 8.434387)
			res.shouldHaveAtomNear("CE2", 10.053614, -2.401242, 7.470743)
			res.shouldHaveAtomNear("CE3", 9.200747, -0.883001, 5.784103)
			res.shouldHaveAtomNear("HE1", 10.093753, -3.801440, 9.054872)
			res.shouldHaveAtomNear("CZ2", 11.378703, -2.130800, 7.114313)
			res.shouldHaveAtomNear("CZ3", 10.519567, -0.612321, 5.431754)
			res.shouldHaveAtomNear("HE3", 8.367570, -0.393433, 5.258512)
			res.shouldHaveAtomNear("HZ2", 12.219517, -2.616621, 7.631324)
			res.shouldHaveAtomNear("CH2", 11.590892, -1.237031, 6.095765)
			res.shouldHaveAtomNear("HZ3", 10.727176, 0.101402, 4.621522)
			res.shouldHaveAtomNear("HH2", 12.622241, -1.003771, 5.791199)
			res.shouldHaveAtomNear("H", 3.958053, -0.848456, 6.691013)
		}

		test("proline") {

			val (res, pos) = instance()

			val frag = conflib.fragments.getValue("PRO")
			val conf = frag.confs.getValue("up")

			// check the anchors
			pos.findAnchorMatch(frag)!!.shouldHaveAnchors_CAN()

			// do eeeet!
			pos.setConf(frag, conf)

			// does this look like proline?
			// this part of the backbone has TOTALLY the wrong phi/psi conformation to support proline,
			// but otherwise, the mutation looks correct
			res.atoms.size shouldBe 14
			res.shouldHaveAtomNear("N", 4.400000, -0.515000, 7.533000)
			res.shouldHaveAtomNear("CA", 5.791000, -0.751000, 7.871000)
			res.shouldHaveAtomNear("C", 6.672000, 0.451000, 7.612000)
			res.shouldHaveAtomNear("O", 7.716000, 0.674000, 8.236000)
			res.shouldHaveAtomNear("HB2", 6.767823, -0.709808, 5.915893)
			res.shouldHaveAtomNear("CD", 4.057314, -1.050517, 6.195225)
			res.shouldHaveAtomNear("CB", 6.341479, -1.441661, 6.618264)
			res.shouldHaveAtomNear("CG", 5.120036, -2.119505, 6.020026)
			res.shouldHaveAtomNear("HG3", 4.860926, -3.041351, 6.561597)
			res.shouldHaveAtomNear("HA", 5.824257, -1.381019, 8.776299)
			res.shouldHaveAtomNear("HD2", 3.040669, -1.470432, 6.176994)
			res.shouldHaveAtomNear("HG2", 5.272675, -2.369463, 4.959621)
			res.shouldHaveAtomNear("HB3", 7.115632, -2.181009, 6.872731)
			res.shouldHaveAtomNear("HD3", 4.125218, -0.268849, 5.424115)
		}

		test("valine->serine") {

			val (res, pos) = instance()

			// mutate to valine
			var frag = conflib.fragments.getValue("VAL")
			var conf = frag.confs.getValue("t")
			pos.setConf(frag, conf)

			// mutate to serine
			frag = conflib.fragments.getValue("SER")
			conf = frag.confs.getValue("t_60")
			pos.setConf(frag, conf)

			// does this look like serine?
			res.shouldHaveAtomNear("N", 4.400000, -0.515000, 7.533000)
			res.shouldHaveAtomNear("CA", 5.791000, -0.751000, 7.871000)
			res.shouldHaveAtomNear("C", 6.672000, 0.451000, 7.612000)
			res.shouldHaveAtomNear("O", 7.716000, 0.674000, 8.236000)
			res.shouldHaveAtomNear("HA", 5.839607, -0.973788, 8.946599)
			res.shouldHaveAtomNear("CB", 6.332726, -1.937088, 7.077001)
			res.shouldHaveAtomNear("HB2", 5.701465, -2.820324, 7.253298)
			res.shouldHaveAtomNear("HB3", 6.283402, -1.716092, 6.000542)
			res.shouldHaveAtomNear("OG", 7.667992, -2.225024, 7.443905)
			res.shouldHaveAtomNear("HG", 7.706109, -2.447422, 8.418113)
			res.shouldHaveAtomNear("H", 3.958053, -0.848456, 6.691013)
		}

		test("valine->back") {

			val (res, pos) = instance()

			// record all the atom positions
			fun Polymer.Residue.capturePositions() =
				atoms.associate { it.name to Vector3d(it.pos) }
			val wtPositions = res.capturePositions()

			// make the wildtype fragment
			val wtFrag = pos.makeFragment("wt", "WT")

			// mutate to valine
			val frag = conflib.fragments.getValue("VAL")
			val conf = frag.confs.getValue("t")
			pos.setConf(frag, conf)

			// mutate back to wildtype
			pos.setConf(wtFrag, wtFrag.confs.values.first())

			// does this look like the original residue?
			val positions = res.capturePositions()
			positions.keys shouldBe wtPositions.keys
			for ((atomName, atomPos) in wtPositions) {
				res.shouldHaveAtomNear(atomName, atomPos.x, atomPos.y, atomPos.z)
			}
		}
	}
})
