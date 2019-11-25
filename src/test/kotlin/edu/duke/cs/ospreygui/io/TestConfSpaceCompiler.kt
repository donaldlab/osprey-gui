package edu.duke.cs.ospreygui.io

import cuchaz.kludge.tools.abs
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.tools.identityHashMapOf
import edu.duke.cs.molscope.tools.identityHashSetOf
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.absolutely
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.amber.Amber96Params
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.forcefield.eef1.EEF1ForcefieldParams
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.prep.DesignPosition
import edu.duke.cs.ospreygui.prep.Proteins
import edu.duke.cs.ospreygui.relatively
import edu.duke.cs.osprey.confspace.compiled.ConfSpace as CompiledConfSpace
import io.kotlintest.shouldBe


class TestConfSpaceCompiler : SharedSpec({

	// load some amino acid confs
	val conflib = ConfLib.from(OspreyGui.getResourceAsString("conflib/lovell.conflib.toml"))


	/**
	 * The compiled conf space only uses six digits of precision
	 * in some places, and we lose a little to roundoff error,
	 * so an epsilon of 1e-5 should be precise enough to test accuracy here.
	 */
	fun Double.shouldBeEnergy(expected: Double, epsilon: Double = 1e-5) {
		if (this.abs() <= 100.0) {
			this shouldBe expected.absolutely(epsilon)
		} else {
			this shouldBe expected.relatively(epsilon)
		}
	}

	/** Used to compute the expected energies for conformations */
	@Suppress("unused")
	fun Molecule.calcAmber96Energy(): Double {
		val amberParams = Amber96Params()
		val molParams = amberParams.parameterize(this, null)
		return amberParams.calcEnergy(
			identityHashMapOf(this to atoms),
			identityHashMapOf(this to molParams)
		)
	}

	/** Used to compute the expected energies for conformations */
	@Suppress("unused")
	fun Molecule.calcEEF1Energy(): Double {
		val eef1Params = EEF1ForcefieldParams().apply {
			// use the full scale for testing
			scale = 1.0
		}
		val molParams = eef1Params.parameterize(this, null)
		return eef1Params.calcEnergy(
			identityHashMapOf(this to atoms),
			identityHashMapOf(this to molParams)
		)
	}

	/** Used to compute the expected energies for conformations */
	@Suppress("unused")
	fun Molecule.dumpEnergies(vararg assignments: Pair<DesignPosition,String>) {

		// set the conformations
		for ((pos, assignment) in assignments) {
			val (fragId, confId) = assignment.split(":")
			val frag = conflib.fragments.getValue(fragId)
			val conf = frag.confs.getValue(confId)
			pos.setConf(frag, conf)
		}

		// calculate and show the energies
		println("amber96: ${calcAmber96Energy()}")
		println("EEF1:    ${calcEEF1Energy()}")
	}


	fun ConfSpace.compile(): CompiledConfSpace {

		// compile the conf space
		val toml = ConfSpaceCompiler(this).run {

			addForcefield(Forcefield.Amber96)

			addForcefield(Forcefield.EEF1).run {
				this as EEF1ForcefieldParams
				// use the full scale for testing EEF1
				scale = 1.0
			}

			compile().run {
				errors.size shouldBe 0
				toml
			}
		}

		// send it to osprey to rebuild the conf space
		return CompiledConfSpace(toml)
	}

	fun CompiledConfSpace.assign(vararg confIds: String) =
		assign(
			confIds
				.mapIndexed { i, confId -> positions[i].findConfOrThrow(confId).index }
				.toIntArray()
		)
	fun CompiledConfSpace.AssignedCoords.calcAmber96() = confSpace.ecalcs[0].calcEnergy(this)
	fun CompiledConfSpace.AssignedCoords.calcEEF1() = confSpace.ecalcs[1].calcEnergy(this)


	// this essentially tests the static energy calculation
	group("1cc8 no positions") {

		val mol = Molecule.fromOMOL(OspreyGui.getResourceAsString("1cc8.protein.omol.toml"))[0] as Polymer

		// get the one conformation
		val conf = ConfSpace(listOf(MoleculeType.Protein to mol))
			.compile()
			.assign()

		test("amber") {
			conf.calcAmber96().shouldBeEnergy(-489.08432295295387)
		}

		test("eef1") {
			conf.calcEEF1().shouldBeEnergy(-691.5469503851598)
		}
	}

	group("glycine dipeptide") {

		val mol = Molecule.fromOMOL(OspreyGui.getResourceAsString("preppedMols/gly-gly.omol.toml"))[0] as Polymer

		group("no positions") {

			// get the one conformation
			val conf = ConfSpace(listOf(MoleculeType.Protein to mol))
				.compile()
				.assign()

			test("amber") {
				conf.calcAmber96().shouldBeEnergy(-2.908253272320646)
			}

			test("eef1") {
				conf.calcEEF1().shouldBeEnergy(-47.75509989567506)
			}
		}

		group("two positions") {

			val res72 = mol.findChainOrThrow("A").findResidueOrThrow("72")
			val res73 = mol.findChainOrThrow("A").findResidueOrThrow("73")
			val pos1 = Proteins.makeDesignPosition(mol, res72, "Pos1")
			val pos2 = Proteins.makeDesignPosition(mol, res73, "Pos2")

			// make the wildtype fragments
			val wtFrag1 = pos1.makeFragment("wt1", "WildType")
			val wtFrag2 = pos2.makeFragment("wt2", "WildType")

			val confSpace = ConfSpace(listOf(MoleculeType.Protein to mol)).apply {

				// make two design positions for the dipeptide
				designPositionsByMol[mol] = mutableListOf(pos1, pos2)

				// configure pos 1
				positionConfSpaces.getOrMake(pos1).run {

					// add the wt frag
					wildTypeFragment = wtFrag1

					// add some mutations
					val gly = conflib.fragments.getValue("GLYn")
					val asp = conflib.fragments.getValue("ASPn")
					val ser = conflib.fragments.getValue("SERn")
					mutations.add(gly.type)
					mutations.add(asp.type)
					mutations.add(ser.type)

					// add some confs
					confs[wtFrag1] = identityHashSetOf(wtFrag1.confs.values.first())
					confs[gly] = gly.getConfs("GLY")
					confs[asp] = asp.getConfs("p30", "t70", "m-20")
					confs[ser] = ser.getConfs("p_-60", "p_180", "t_0", "m_-60")
				}

				// configure pos 2
				positionConfSpaces.getOrMake(pos2).run {

					// add the wt frag
					wildTypeFragment = wtFrag2

					// add some mutations
					val gly = conflib.fragments.getValue("GLY")
					val leu = conflib.fragments.getValue("LEU")
					val ala = conflib.fragments.getValue("ALA")
					val pro = conflib.fragments.getValue("PRO")
					mutations.add(gly.type)
					mutations.add(leu.type)
					mutations.add(ala.type)
					mutations.add(pro.type)

					// add some confs
					confs[wtFrag2] = identityHashSetOf(wtFrag2.confs.values.first())
					confs[gly] = gly.getConfs("GLY")
					confs[leu] = leu.getConfs("pp", "tp", "tt")
					confs[ala] = ala.getConfs("ALA")
					confs[pro] = pro.getConfs("down", "up")
				}
			}
			val compiledConfSpace = confSpace.compile()

			test("wt-wt") {
				compiledConfSpace.assign("wt1:wt1", "wt2:wt2").run {
					calcAmber96().shouldBeEnergy(-2.9082532723206453)
					calcEEF1().shouldBeEnergy(-47.75509989567506)
				}
			}

			test("asp:p30-leu:tt") {
				compiledConfSpace.assign("ASPn:p30", "LEU:tt").run {
					calcAmber96().shouldBeEnergy(31.328372547103974)
					calcEEF1().shouldBeEnergy(-57.897054356496625)
				}
			}

			test("asp:m-20-leu:tp") {
				compiledConfSpace.assign("ASPn:m-20", "LEU:tp").run {
					calcAmber96().shouldBeEnergy(-2.4030562287427513)
					calcEEF1().shouldBeEnergy(-59.597396462270645)
				}
			}

			test("ser:t_0-pro:up") {
				compiledConfSpace.assign("SERn:t_0", "PRO:up").run {
					calcAmber96().shouldBeEnergy(3681646.881490728)
					calcEEF1().shouldBeEnergy(-41.52398320030191)
				}
			}
		}
	}
})
