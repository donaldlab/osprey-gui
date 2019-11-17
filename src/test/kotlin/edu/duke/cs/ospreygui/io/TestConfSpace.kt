package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.tools.identityHashMapOf
import edu.duke.cs.molscope.tools.identityHashSet
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.prep.DesignPosition
import edu.duke.cs.ospreygui.prep.Proteins
import io.kotlintest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotlintest.shouldBe
import org.joml.Vector3d


/** This conf space has a little bit of everything! */
fun makeTestConfSpace(): ConfSpace {

	// load some molecules
	val protein = Molecule.fromOMOL(OspreyGui.getResourceAsString("1cc8.protein.omol.toml"))[0] as Polymer
	val smallmol = Molecule.fromOMOL(OspreyGui.getResourceAsString("benzamidine.omol.toml"))[0]

	// load some confs
	val conflib = ConfLib.from(OspreyGui.getResourceAsString("conflib/lovell.conflib.toml"))

	// make the conf space
	return ConfSpace(listOf(
		MoleculeType.Protein to protein,
		MoleculeType.SmallMolecule to smallmol
	)).apply {

		name = "The Awesomest Conformation Space Evarrrr!!!"

		// add some positions to the protein
		val leu26 = protein.findChainOrThrow("A").findResidueOrThrow("26")
		val thr27 = protein.findChainOrThrow("A").findResidueOrThrow("27")
		val pos1 = Proteins.makeDesignPosition(protein, leu26, "Pos1")
		val pos2 = Proteins.makeDesignPosition(protein, thr27, "Pos2")
		designPositionsByMol[protein] = mutableListOf(pos1, pos2)

		// set the pos conf spaces
		positionConfSpaces.getOrMake(pos1).apply {

			val leu = conflib.fragments.getValue("LEU")
			val ala = conflib.fragments.getValue("ALA")
			val pro = conflib.fragments.getValue("PRO")

			// add the wt frag
			val wtFrag = pos1.makeFragment("wt-${pos1.name.toTomlKey()}", "WildType", dofs = leu.dofs)
			wildTypeFragment = wtFrag

			// add some mutations
			mutations.add(ala.type)
			mutations.add(pro.type)

			// add some confs
			confs[wtFrag] = wtFrag.confs.values.toCollection(identityHashSet())
			confs[leu] = leu.getConfs("pp", "tp", "tt")
			confs[ala] = ala.getConfs("ALA")
			confs[pro] = pro.getConfs("down", "up")

			// add some continuous flexibility
			dofSettings[wtFrag] = ConfSpace.PositionConfSpace.DofSettings(
				includeHGroupRotations = false,
				dihedralRadiusDegrees = 9.0
			)
			dofSettings[leu] = ConfSpace.PositionConfSpace.DofSettings(
				includeHGroupRotations = false,
				dihedralRadiusDegrees = 9.0
			)
			dofSettings[ala] = ConfSpace.PositionConfSpace.DofSettings(
				includeHGroupRotations = true,
				dihedralRadiusDegrees = 30.0
			)
		}
		positionConfSpaces.getOrMake(pos2).apply {

			val gly = conflib.fragments.getValue("GLY")

			// set only one mutation
			mutations.add(gly.type)

			// add the conf
			confs[gly] = gly.getConfs("GLY")

			// no continuous flexibility, it's glycine ...
		}

		// add a position to the small mol
		val pos3 = DesignPosition(
			name = "Pos3",
			type = "FOO",
			mol = smallmol
		).apply {

			// add a single anchor
			anchorGroups.add(mutableListOf(
				anchorSingle(
					smallmol.atoms.findOrThrow("C2"),
					smallmol.atoms.findOrThrow("C1"),
					smallmol.atoms.findOrThrow("C3")
				)
			))

			// add the current atom
			currentAtoms.add(smallmol.atoms.findOrThrow("H10"))
		}
		designPositionsByMol[smallmol] = mutableListOf(pos3)

		// set the pos conf spaces
		positionConfSpaces.getOrMake(pos3).apply {

			// add the wt frag
			val wtFrag = pos3.makeFragment("wt-${pos3.name.toTomlKey()}", "WildType")
			wildTypeFragment = wtFrag

			// add a mutation
			mutations.add("BAR")

			// make a fragment with a silly oxygen triangle
			val ha1 = ConfLib.AtomInfo(1, "OA1", Element.Oxygen)
			val ha2 = ConfLib.AtomInfo(2, "OA2", Element.Oxygen)
			val anchor = ConfLib.Anchor.Single(
				id = 1,
				bonds = listOf(ha1, ha2)
			)
			val bar = ConfLib.Fragment(
				id = "bar",
				name = "Bar",
				type = "BAR",
				atoms = listOf(ha1, ha2),
				bonds = listOf(ConfLib.Bond(ha1, ha2)),
				anchors = listOf(anchor),
				confs = mapOf(
					"bar1" to ConfLib.Conf(
						id = "bar1",
						name = "Bar 1",
						description = null,
						coords = identityHashMapOf(
							// none of the actual coords matter at all
							ha1 to Vector3d(1.0, 2.0, 3.0),
							ha2 to Vector3d(4.0, 5.0, 6.0)
						),
						anchorCoords = identityHashMapOf(
							anchor to ConfLib.AnchorCoords.Single(
								// NOTE: don't make anchor atoms co-linear
								a = Vector3d(1.2, 1.3, 1.4),
								b = Vector3d(2.2, 2.3, 2.4),
								c = Vector3d(3.2, 3.3, 0.4)
							)
						)
					),
					"bar2" to ConfLib.Conf(
						id = "bar2",
						name = "Bar 2",
						description = "Bar 2 description, yup",
						coords = identityHashMapOf(
							// none of the actual coords matter at all
							ha1 to Vector3d(1.5, 2.5, 3.5),
							ha2 to Vector3d(4.5, 5.5, 6.5)
						),
						anchorCoords = identityHashMapOf(
							anchor to ConfLib.AnchorCoords.Single(
								// NOTE: don't make anchor atoms co-linear
								a = Vector3d(4.2, 4.3, 4.4),
								b = Vector3d(5.2, 5.3, 5.4),
								c = Vector3d(6.2, 6.3, 3.4)
							)
						)
					)
				),
				dofs = emptyList()
			)

			// add some confs
			confs[wtFrag] = wtFrag.confs.values.toMutableSet()
			confs[bar] = bar.getConfs("bar1", "bar2")

			// no DoF settings, ie no continuous flexibility
		}
	}
}

class TestConfSpace : SharedSpec({

	test("roundtrip") {

		// do the roundtrip
		val expConfSpace = makeTestConfSpace()
		val toml = expConfSpace.toToml()
		val obsConfSpace = ConfSpace.fromToml(toml)

		// make sure we got the same conf space back
		obsConfSpace.name shouldBe expConfSpace.name

		// check the molecules
		obsConfSpace.mols
			.map { (type, mol) -> type to mol.type }
			.shouldBe(
				expConfSpace.mols
				.map { (type, mol) -> type to mol.type }
			)

		// check the design positions
		obsConfSpace.positions().size shouldBe expConfSpace.positions().size
		for ((obsPos, expPos) in obsConfSpace.positions().zip(expConfSpace.positions())) {

			obsPos.name shouldBe expPos.name
			obsPos.type shouldBe expPos.type
			obsPos.mol.name shouldBe expPos.mol.name

			// check the anchor groups
			obsPos.anchorGroups.size shouldBe expPos.anchorGroups.size
			for ((obsGroup, expGroup) in obsPos.anchorGroups.zip(expPos.anchorGroups)) {

				obsGroup.size shouldBe expGroup.size
				for ((obsAnchor, expAnchor) in obsGroup.zip(expGroup)) {

					obsAnchor::class shouldBe expAnchor::class
					obsAnchor.anchorAtoms shouldBe expAnchor.anchorAtoms
				}
			}

			// check the atoms
			// re-do the sets to drop the indentity comparisons in favor of value comparisons
			obsPos.currentAtoms.toSet() shouldBe expPos.currentAtoms.toSet()

			// check the position conf spaces
			val obsPosConfSpace = obsConfSpace.positionConfSpaces[obsPos]!!
			val expPosConfSpace = expConfSpace.positionConfSpaces[expPos]!!

			obsPosConfSpace.wildTypeFragment shouldBeFrag expPosConfSpace.wildTypeFragment
			obsPosConfSpace.mutations shouldBe expPosConfSpace.mutations

			// check the confs
			obsPosConfSpace.confs.size shouldBe expPosConfSpace.confs.size
			val fragIds = obsPosConfSpace.confs.map { (frag, _) -> frag.id }
			fragIds shouldContainExactlyInAnyOrder expPosConfSpace.confs.map { (frag, _) -> frag.id }
			for (fragId in fragIds) {

				// check the fragments
				val obsFrag = obsPosConfSpace.confs.keys.find { it.id == fragId }!!
				val expFrag = expPosConfSpace.confs.keys.find { it.id == fragId }!!
				obsFrag shouldBeFrag expFrag

				// check the fragment confs
				val obsConfs = obsPosConfSpace.confs[obsFrag]!!.sortedBy { it.id }
				val expConfs = expPosConfSpace.confs[expFrag]!!.sortedBy { it.id }
				obsConfs.size shouldBe expConfs.size
				for ((obsConf, expConf) in obsConfs.zip(expConfs)) {
					obsConf shouldBeConf expConf
				}
			}
		}
	}
})