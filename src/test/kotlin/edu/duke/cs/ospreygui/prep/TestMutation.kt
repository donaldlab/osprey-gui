package edu.duke.cs.ospreygui.prep

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.dofs.dihedralAngle
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.io.fromOMOL
import edu.duke.cs.ospreygui.show
import io.kotlintest.matchers.beLessThanOrEqualTo
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.should
import io.kotlintest.shouldBe
import org.joml.Vector3d


class TestMutation : SharedSpec({

	fun Vector3d.shouldBeNear(p: Vector3d) =
		distance(p) should beLessThanOrEqualTo(1e-3)

	fun Vector3d.shouldBeNear(x: Double, y: Double, z: Double) =
		shouldBeNear(Vector3d(x, y, z))

	fun Polymer.Residue.shouldHaveAtomNear(atomName: String, p: Vector3d) =
		findAtomOrThrow(atomName).pos.shouldBeNear(p)

	fun Polymer.Residue.shouldHaveAtomNear(atomName: String, x: Double, y: Double, z: Double) =
		shouldHaveAtomNear(atomName, Vector3d(x, y, z))

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

	fun Polymer.Residue.capturePositions() =
		atoms.associate { it.name to Vector3d(it.pos) }


	group("protein") {

		val conflib = ConfLib.from(OspreyGui.getResourceAsString("conflib/lovell.conflib.toml"))
		val protein1cc8 = Molecule.fromOMOL(OspreyGui.getResourceAsString("1cc8.protein.omol.toml"))[0] as Polymer

		data class Instance(
			val res: Polymer.Residue,
			val pos: DesignPosition
		)
		fun ala2(): Instance {

			// copy the molecule, so we don't destroy the original
			val mol = protein1cc8.copy()
			val res = mol.findChainOrThrow("A").findResidueOrThrow("2")

			// make the design position
			val pos = Proteins.makeDesignPosition(mol, res, "ala2")

			// the current atoms should be glycine sidechain atoms
			pos.currentAtoms.map { it.name }.toSet() shouldBe setOf("HA", "CB", "HB1", "HB2", "HB3")

			return Instance(res, pos)
		}
		fun gly17(): Instance {

			// copy the molecule, so we don't destroy the original
			val mol = protein1cc8.copy()
			val res = mol.findChainOrThrow("A").findResidueOrThrow("17")

			// make the design position
			val pos = Proteins.makeDesignPosition(mol, res, "gly17")
			
			// the current atoms should be glycine sidechain atoms
			pos.currentAtoms.map { it.name }.toSet() shouldBe setOf("HA2", "HA3", "H")
			
			return Instance(res, pos)
		}
		fun pro52(): Instance {

			// copy the molecule, so we don't destroy the original
			val mol = protein1cc8.copy()
			val res = mol.findChainOrThrow("A").findResidueOrThrow("52")

			// make the design position
			val pos = Proteins.makeDesignPosition(mol, res, "pro52")

			// the current atoms should be proline sidechain atoms
			pos.currentAtoms.map { it.name }.toSet() shouldBe setOf("CD", "HD2", "HD3", "CG", "HG2", "HG3", "CB", "HB2", "HB3", "HA")

			return Instance(res, pos)
		}

		fun DesignPosition.AnchorMatch.shouldHaveAnchors_CA_N(namesCA: Set<String>, namesN: Set<String>) {

			// should have gotten the 2x single anchors
			pairs.size shouldBe 2

			val (posAnchorCA, fragAnchorCA) = pairs[0]
			posAnchorCA.shouldBeTypeOf<DesignPosition.Anchor.Single> {
				it.a.name shouldBe "CA"
				it.getConnectedAtoms().map { it.name }.toSet() shouldBe namesCA
			}
			fragAnchorCA.shouldBeTypeOf<ConfLib.Anchor.Single> {
				it.id shouldBe 1
			}

			val (posAnchorN, fragAnchorN) = pairs[1]
			posAnchorN.shouldBeTypeOf<DesignPosition.Anchor.Single> {
				it.a.name shouldBe "N"
				it.getConnectedAtoms().map { it.name }.toSet() shouldBe namesN
			}
			fragAnchorN.shouldBeTypeOf<ConfLib.Anchor.Single> {
				it.id shouldBe 2
			}
		}

		fun DesignPosition.AnchorMatch.shouldHaveAnchors_CA(namesCA: Set<String>) {

			// should have gotten the 1x single anchor
			pairs.size shouldBe 1

			val (posAnchorCA, fragAnchorCA) = pairs[0]
			posAnchorCA.shouldBeTypeOf<DesignPosition.Anchor.Single> {
				it.a.name shouldBe "CA"
				it.getConnectedAtoms().map { it.name }.toSet() shouldBe namesCA
			}
			fragAnchorCA.shouldBeTypeOf<ConfLib.Anchor.Single> {
				it.id shouldBe 1
			}
		}

		fun DesignPosition.AnchorMatch.shouldHaveAnchors_CAN(names: Set<String>) {

			// should have gotten the 1x double anchor
			pairs.size shouldBe 1

			val (posAnchor, fragAnchor) = pairs[0]
			posAnchor.shouldBeTypeOf<DesignPosition.Anchor.Double> {
				it.a.name shouldBe "CA"
				it.b.name shouldBe "N"
				it.getConnectedAtoms().map { it.name }.toSet() shouldBe names
			}
			fragAnchor.shouldBeTypeOf<ConfLib.Anchor.Double> {
				it.id shouldBe 1
			}
		}

		// just spot-check a few amino acids

		test("glycine->glycine") {

			val (res, pos) = gly17()

			// find the glycine conformation in the library
			val frag = conflib.fragments.getValue("GLY")
			val conf = frag.confs.getValue("GLY")

			// check the anchors
			pos.findAnchorMatch(frag)!!.shouldHaveAnchors_CA_N(
				namesCA = setOf("HA2", "HA3"),
				namesN = setOf("H")
			)

			// do eeeet!
			pos.setConf(frag, conf)

			// does this look like glycine?
			res.type shouldBe "GLY"
			res.atoms.size shouldBe 7
			res.shouldHaveAtomNear("N", 4.400000, -0.515000, 7.533000)
			res.shouldHaveAtomNear("CA", 5.791000, -0.751000, 7.871000)
			res.shouldHaveAtomNear("C", 6.672000, 0.451000, 7.612000)
			res.shouldHaveAtomNear("O", 7.716000, 0.674000, 8.236000)
			res.shouldHaveAtomNear("HA2", 5.865571, -1.028786, 8.932637)
			res.shouldHaveAtomNear("HA3", 6.167383, -1.604053, 7.287993)
			res.shouldHaveAtomNear("H", 3.958053, -0.848456, 6.691013)
		}

		test("glycine->valine") {

			val (res, pos) = gly17()

			val frag = conflib.fragments.getValue("VAL")
			val conf = frag.confs.getValue("t")

			// check the anchors
			pos.findAnchorMatch(frag)!!.shouldHaveAnchors_CA_N(
				namesCA = setOf("HA2", "HA3"),
				namesN = setOf("H")
			)

			// do eeeet!
			pos.setConf(frag, conf)

			// does this look like valine?
			res.type shouldBe "VAL"
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

		test("glycine->tryptophan") {

			val (res, pos) = gly17()

			val frag = conflib.fragments.getValue("TRP")
			val conf = frag.confs.getValue("t90")

			// check the anchors
			pos.findAnchorMatch(frag)!!.shouldHaveAnchors_CA_N(
				namesCA = setOf("HA2", "HA3"),
				namesN = setOf("H")
			)

			// do eeeet!
			pos.setConf(frag, conf)

			// does this look like tryptophan?
			res.type shouldBe "TRP"
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

		test("glycine->proline") {

			val (res, pos) = gly17()

			val frag = conflib.fragments.getValue("PRO")
			val conf = frag.confs.getValue("up")

			// check the anchors
			pos.findAnchorMatch(frag)!!.shouldHaveAnchors_CAN(
				names = setOf("HA2", "HA3", "H")
			)

			// do eeeet!
			pos.setConf(frag, conf)

			// does this look like proline?
			// this part of the backbone has TOTALLY the wrong phi/psi conformation to support proline,
			// but otherwise, the mutation looks correct
			res.type shouldBe "PRO"
			res.atoms.size shouldBe 14
			res.shouldHaveAtomNear("N", 4.400000, -0.515000, 7.533000)
			res.shouldHaveAtomNear("CA", 5.791000, -0.751000, 7.871000)
			res.shouldHaveAtomNear("C", 6.672000, 0.451000, 7.612000)
			res.shouldHaveAtomNear("O", 7.716000, 0.674000, 8.236000)
			res.shouldHaveAtomNear("HG2", 5.006665, -3.076945, 5.560371)
			res.shouldHaveAtomNear("CB", 6.224608, -1.804685, 6.845759)
			res.shouldHaveAtomNear("HB3", 6.965117, -2.499234, 7.269964)
			res.shouldHaveAtomNear("HD2", 2.908340, -1.706927, 6.556454)
			res.shouldHaveAtomNear("HA", 5.837578, -1.102552, 8.915911)
			res.shouldHaveAtomNear("CG", 4.931207, -2.528918, 6.511270)
			res.shouldHaveAtomNear("HG3", 4.647473, -3.239651, 7.301579)
			res.shouldHaveAtomNear("CD", 3.948287, -1.376688, 6.416172)
			res.shouldHaveAtomNear("HB2", 6.653983, -1.333461, 5.948938)
			res.shouldHaveAtomNear("HD3", 4.019604, -0.850028, 5.452965)
		}

		test("glycine->proline->back") {

			val (res, pos) = gly17()

			// record all the atom positions
			val wtPositions = res.capturePositions()

			// make the wildtype fragment
			val wtFrag = pos.makeFragment("wt", "WT")
			val wtConf = wtFrag.confs.values.first()

			// check the fragment anchors
			wtFrag.anchors.run {
				size shouldBe 2
				this[0].shouldBeTypeOf<ConfLib.Anchor.Single>()
				this[1].shouldBeTypeOf<ConfLib.Anchor.Single>()
			}

			// mutate to proline
			val frag = conflib.fragments.getValue("PRO")
			val conf = frag.confs.getValue("up")
			pos.setConf(frag, conf)

			// mutate back to wildtype
			pos.setConf(wtFrag, wtConf)

			// does this look like the original residue?
			res.type shouldBe "GLY"
			res.atoms.size shouldBe wtPositions.size
			for ((atomName, atomPos) in wtPositions) {
				res.shouldHaveAtomNear(atomName, atomPos)
			}
		}

		test("glycine->valine->serine") {

			val (res, pos) = gly17()

			// mutate to valine
			var frag = conflib.fragments.getValue("VAL")
			var conf = frag.confs.getValue("t")
			pos.setConf(frag, conf)

			// mutate to serine
			frag = conflib.fragments.getValue("SER")
			conf = frag.confs.getValue("t_60")
			pos.setConf(frag, conf)

			// does this look like serine?
			res.type shouldBe "SER"
			res.atoms.size shouldBe 11
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

		test("glycine->valine->back") {

			val (res, pos) = gly17()

			// record all the atom positions
			val wtPositions = res.capturePositions()

			// make the wildtype fragment
			val wtFrag = pos.makeFragment("wt", "WT")
			val wtConf = wtFrag.confs.values.first()

			// check the fragment anchors
			wtFrag.anchors.run {
				size shouldBe 2
				this[0].shouldBeTypeOf<ConfLib.Anchor.Single>()
				this[1].shouldBeTypeOf<ConfLib.Anchor.Single>()
			}

			// mutate to valine
			val frag = conflib.fragments.getValue("VAL")
			val conf = frag.confs.getValue("t")
			pos.setConf(frag, conf)

			// mutate back to wildtype
			pos.setConf(wtFrag, wtConf)

			// does this look like the original residue?
			res.type shouldBe "GLY"
			res.atoms.size shouldBe wtPositions.size
			for ((atomName, atomPos) in wtPositions) {
				res.shouldHaveAtomNear(atomName, atomPos)
			}
		}

		test("proline->glycine") {

			val (res, pos) = pro52()

			// find the glycine conformation in the library
			val frag = conflib.fragments.getValue("GLY")
			val conf = frag.confs.getValue("GLY")

			// check the anchors
			pos.findAnchorMatch(frag)!!.shouldHaveAnchors_CA_N(
				namesCA = setOf("HB2", "CD", "CB", "CG", "HG3", "HA", "HD2", "HG2", "HB3", "HD3"),
				namesN = setOf("HB2", "CD", "CB", "CG", "HG3", "HD2", "HG2", "HB3", "HD3")
			)

			// do eeeet!
			pos.setConf(frag, conf)

			// does this look like glycine?
			res.type shouldBe "GLY"
			res.atoms.size shouldBe 7
			res.shouldHaveAtomNear("N", 17.783000, 20.016000, 15.734000)
			res.shouldHaveAtomNear("CA", 17.915000, 20.746000, 14.478000)
			res.shouldHaveAtomNear("C", 17.289000, 20.039000, 13.277000)
			res.shouldHaveAtomNear("O", 17.273000, 18.803000, 13.190000)
			res.shouldHaveAtomNear("HA2", 17.447047, 21.736009, 14.581452)
			res.shouldHaveAtomNear("HA3", 18.980680, 20.914459, 14.265382)
			res.shouldHaveAtomNear("H", 18.497148, 19.433047, 16.140951)
		}

		test("proline->glycine->back") {

			val (res, pos) = pro52()

			// record all the atom positions
			val wtPositions = res.capturePositions()

			// make the wildtype fragment
			val wtFrag = pos.makeFragment("wt", "WT")
			val wtConf = wtFrag.confs.values.first()

			// check the fragment anchors
			wtFrag.anchors.run {
				size shouldBe 1
				this[0].shouldBeTypeOf<ConfLib.Anchor.Double> { confAnchor ->
					wtConf.anchorCoords[confAnchor].shouldBeTypeOf<ConfLib.AnchorCoords.Double> { confCoords ->
						res.shouldHaveAtomNear("CA", confCoords.a)
						res.shouldHaveAtomNear("N", confCoords.b)
						protein1cc8.findChainOrThrow("A").findResidueOrThrow("51") // C in previous residue
							.shouldHaveAtomNear("C", confCoords.c)
						res.shouldHaveAtomNear("C", confCoords.d)
					}
				}
			}

			// mutate to glycine
			val frag = conflib.fragments.getValue("GLY")
			val conf = frag.confs.getValue("GLY")
			pos.setConf(frag, conf)

			// mutate back to wildtype
			pos.setConf(wtFrag, wtConf)

			// does this look like the original residue?
			res.type shouldBe "PRO"
			res.atoms.size shouldBe wtPositions.size
			for ((atomName, atomPos) in wtPositions) {
				res.shouldHaveAtomNear(atomName, atomPos)
			}
		}

		test("Nala->glycine") {

			val (res, pos) = ala2()

			// find the glycine conformation in the library
			val frag = conflib.fragments.getValue("GLYn")
			val conf = frag.confs.getValue("GLY")

			// check the anchors
			pos.findAnchorMatch(frag)!!.shouldHaveAnchors_CA(
				namesCA = setOf("HA", "CB", "HB1", "HB2", "HB3")
			)

			// do eeeet!
			pos.setConf(frag, conf)

			// does this look like glycine?
			res.type shouldBe "GLY"
			res.atoms.size shouldBe 9
			res.shouldHaveAtomNear("N", 14.789000, 27.073000, 24.130000)
			res.shouldHaveAtomNear("CA", 13.936000, 25.892000, 24.216000)
			res.shouldHaveAtomNear("C", 12.753000, 25.845000, 23.241000)
			res.shouldHaveAtomNear("O", 11.656000, 25.370000, 23.562000)
			res.shouldHaveAtomNear("H1", 14.423345, 27.671948, 23.403599)
			res.shouldHaveAtomNear("H2", 15.693612, 26.728407, 23.841847)
			res.shouldHaveAtomNear("H3", 14.686749, 27.541349, 25.018984)
			res.shouldHaveAtomNear("HA2", 14.542628, 24.991303, 24.041222)
			res.shouldHaveAtomNear("HA3", 13.526968, 25.811973, 25.233619)
		}

		test("Nala->glycine->back") {

			val (res, pos) = ala2()

			// record all the atom positions
			val wtPositions = res.capturePositions()

			// make the wildtype fragment
			val wtFrag = pos.makeFragment("wt", "WT")
			val wtConf = wtFrag.confs.values.first()

			// check the fragment anchors
			wtFrag.anchors.run {
				size shouldBe 1
				this[0].shouldBeTypeOf<ConfLib.Anchor.Single>()
			}

			// mutate to glycine
			val frag = conflib.fragments.getValue("GLYn")
			val conf = frag.confs.getValue("GLY")
			pos.setConf(frag, conf)

			// mutate back to wildtype
			pos.setConf(wtFrag, wtConf)

			// does this look like the original residue?
			res.type shouldBe "ALA"
			res.atoms.size shouldBe wtPositions.size
			for ((atomName, atomPos) in wtPositions) {
				res.shouldHaveAtomNear(atomName, atomPos)
			}
		}

		test("glycine->valine chi1") {

			val (res, pos) = gly17()

			// mutate to valine
			val frag = conflib.fragments.getValue("VAL")
			val conf = frag.confs.getValue("t")
			pos.setConf(frag, conf)

			// build the dihedral angle
			val chi1 = frag.dofs[0] as ConfLib.DegreeOfFreedom.DihedralAngle
			pos.dihedralAngle(chi1).run {
				mol shouldBe pos.mol
				a.name shouldBe "N"
				b.name shouldBe "CA"
				c.name shouldBe "CB"
				d.name shouldBe "CG1"
			}
		}
	}

	// TODO: small molecules?
})
