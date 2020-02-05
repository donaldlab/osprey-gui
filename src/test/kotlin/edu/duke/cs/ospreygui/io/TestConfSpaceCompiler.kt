package edu.duke.cs.ospreygui.io

import cuchaz.kludge.tools.abs
import cuchaz.kludge.tools.toDegrees
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.tools.identityHashMapOf
import edu.duke.cs.molscope.tools.identityHashSet
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
import io.kotlintest.matchers.doubles.shouldBeLessThan
import io.kotlintest.matchers.types.shouldBeTypeOf
import edu.duke.cs.osprey.confspace.compiled.ConfSpace as CompiledConfSpace
import edu.duke.cs.osprey.confspace.compiled.AssignedCoords
import edu.duke.cs.osprey.confspace.compiled.motions.DihedralAngle as CompiledDihedralAngle
import edu.duke.cs.osprey.energy.compiled.CPUConfEnergyCalculator
import edu.duke.cs.osprey.energy.compiled.PosInterGen
import edu.duke.cs.ospreygui.compiler.ConfSpaceCompiler
import edu.duke.cs.ospreygui.motions.DihedralAngle
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
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
	fun Molecule.dumpEnergies() {

		// calculate and show the energies
		val amber96 = calcAmber96Energy()
		val eef1 = calcEEF1Energy()
		println("""
			|amber96:   $amber96
			|EEF1:      $eef1
			|combined:  ${amber96 + eef1}
		""".trimMargin())
	}

	/** Used to compute the expected energies for conformations */
	@Suppress("unused")
	fun ConfSpace.dumpEnergies(vararg assignments: Pair<DesignPosition,String>) {

		backupPositions(*assignments.map { (pos, _) -> pos }.toTypedArray()) {

			// set the conformations
			for ((pos, id) in assignments) {
				val (fragId, confId) = id.split(":")
				val posConfSpace = positionConfSpaces[pos]!!
				val confConfSpace = posConfSpace.confs.find { it.frag.id == fragId && it.conf.id == confId }!!
				pos.setConf(confConfSpace.frag, confConfSpace.conf)
			}

			// get the molecule from the positions
			val mol = assignments
				.map { (pos, _) -> pos.mol }
				.toCollection(identityHashSet())
				.takeIf { it.size == 1 }
				?.first()
				?: throw Error("positions are in different molecules")

			mol.dumpEnergies()
		}
	}

	fun ConfSpace.compile(): CompiledConfSpace {

		// compile the conf space
		val bytes = ConfSpaceCompiler(this).run {

			addForcefield(Forcefield.Amber96)

			addForcefield(Forcefield.EEF1).run {
				this as EEF1ForcefieldParams
				// use the full scale for testing EEF1
				scale = 1.0
			}

			compile().run {
				waitForFinish()
				report!!.run {
					compiled?.toBytes()
						?: error?.let { throw it }
						?: throw Error("no compiled")
				}
			}
		}

		// send it to osprey to rebuild the conf space
		return CompiledConfSpace.fromBytes(bytes)
	}

	fun CompiledConfSpace.makeCoords(vararg confIds: String) =
		makeCoords(
			confIds
				.mapIndexed { i, confId -> positions[i].findConfOrThrow(confId).index }
				.toIntArray()
		)
	fun AssignedCoords.calcAmber96() = confSpace.ecalcs[0].calcEnergy(this)
	fun AssignedCoords.calcEEF1() = confSpace.ecalcs[1].calcEnergy(this)

	fun AssignedCoords.allInters() = PosInterGen(null, null).all(confSpace, assignments)
	fun AssignedCoords.calcEnergy() = CPUConfEnergyCalculator(confSpace).calcEnergy(assignments, allInters())
	fun AssignedCoords.minimizeEnergy() = CPUConfEnergyCalculator(confSpace).minimizeEnergy(assignments, allInters())

	fun Group.testConf(
		compiledConfSpace: CompiledConfSpace,
		vararg confIds: String,
		focus: Boolean = false,
		block: AssignedCoords.() -> Unit
	) {
		// define the test, and make the conformation coords
		test("conf: " + confIds.joinToString(", "), focus = focus) {
			compiledConfSpace.makeCoords(*confIds).run(block)
		}
	}

	// this essentially tests the static energy calculation
	group("1cc8 no positions") {

		val mol = Molecule.fromOMOL(OspreyGui.getResourceAsString("1cc8.protein.omol.toml"))[0] as Polymer

		// get the one conformation
		val conf = ConfSpace(listOf(MoleculeType.Protein to mol))
			.compile()
			.makeCoords()

		test("amber") {
			conf.calcAmber96().shouldBeEnergy(-489.08432295295387)
		}

		test("eef1") {
			conf.calcEEF1().shouldBeEnergy(-691.5469503851598)
		}
	}

	group("glycine dipeptide") {

		fun loadMol() =
			Molecule.fromOMOL(OspreyGui.getResourceAsString("preppedMols/gly-gly.omol.toml"))[0] as Polymer

		group("no positions") {

			val mol = loadMol()

			// get the one conformation
			val conf = ConfSpace(listOf(MoleculeType.Protein to mol))
				.compile()
				.makeCoords()

			test("amber") {
				conf.calcAmber96().shouldBeEnergy(-2.908253272320646)
			}

			test("eef1") {
				conf.calcEEF1().shouldBeEnergy(-47.75509989567506)
			}
		}

		group("two discrete positions") {

			val mol = loadMol()
			val res72 = mol.findChainOrThrow("A").findResidueOrThrow("72")
			val res73 = mol.findChainOrThrow("A").findResidueOrThrow("73")

			val pos1 = Proteins.makeDesignPosition(mol, res72, "Pos1")
			val pos2 = Proteins.makeDesignPosition(mol, res73, "Pos2")

			val confSpace = ConfSpace(listOf(MoleculeType.Protein to mol)).apply {

				// make two design positions for the dipeptide
				designPositionsByMol[mol] = mutableListOf(pos1, pos2)

				// configure pos 1
				positionConfSpaces.getOrMake(pos1).run {

					// add the wt frag
					val wtFrag1 = pos1.makeFragment("wt1", "WildType")
					wildTypeFragment = wtFrag1

					// add some mutations
					val gly = conflib.fragments.getValue("GLYn")
					val asp = conflib.fragments.getValue("ASPn")
					val ser = conflib.fragments.getValue("SERn")
					mutations.add(gly.type)
					mutations.add(asp.type)
					mutations.add(ser.type)

					// add some confs
					confs.addAll(wtFrag1)
					confs.addAll(gly)
					confs.addAll(asp, "p30", "t70", "m-20")
					confs.addAll(ser, "p_-60", "p_180", "t_0", "m_-60")
				}

				// configure pos 2
				positionConfSpaces.getOrMake(pos2).run {

					// add the wt frag
					val wtFrag2 = pos2.makeFragment("wt2", "WildType")
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
					confs.addAll(wtFrag2)
					confs.addAll(gly)
					confs.addAll(leu, "pp", "tp", "tt")
					confs.addAll(ala)
					confs.addAll(pro)
				}
			}
			val compiledConfSpace = confSpace.compile()

			testConf(compiledConfSpace, "wt1:wt1", "wt2:wt2") {
				calcAmber96().shouldBeEnergy(-2.9082532723206453)
				calcEEF1().shouldBeEnergy(-47.75509989567506)
				calcEnergy().shouldBeEnergy(-2.9082532723206453 + -47.75509989567506)
			}

			testConf(compiledConfSpace, "ASPn:p30", "LEU:tt") {
				calcAmber96().shouldBeEnergy(31.328372547103974)
				calcEEF1().shouldBeEnergy(-57.897054356496625)
				calcEnergy().shouldBeEnergy(31.328372547103974 + -57.897054356496625)
			}

			testConf(compiledConfSpace, "ASPn:m-20", "LEU:tp") {
				calcAmber96().shouldBeEnergy(-2.4030562287427513)
				calcEEF1().shouldBeEnergy(-59.597396462270645)
				calcEnergy().shouldBeEnergy(-2.4030562287427513 + -59.597396462270645)
			}

			testConf(compiledConfSpace, "SERn:t_0", "PRO:up") {
				calcAmber96().shouldBeEnergy(3681646.881490728)
				calcEEF1().shouldBeEnergy(-41.52398320030191)
				calcEnergy().shouldBeEnergy(3681646.881490728 + -41.52398320030191)
			}
		}

		group("one continuous position") {

			val mol = loadMol()
			val res73 = mol.findChainOrThrow("A").findResidueOrThrow("73")
			val pos1 = Proteins.makeDesignPosition(mol, res73, "Pos1")

			val confSpace = ConfSpace(listOf(MoleculeType.Protein to mol)).apply {

				// make one design position for the dipeptide at the C terminus
				designPositionsByMol[mol] = mutableListOf(pos1)

				// configure pos 1
				positionConfSpaces.getOrMake(pos1).run {

					// don't bother with the wild-type, glycine has no dihedrals

					// add some mutations
					val ala = conflib.fragments.getValue("ALA")
					val leu = conflib.fragments.getValue("LEU")
					val lys = conflib.fragments.getValue("LYS")
					mutations.add(ala.type)
					mutations.add(leu.type)
					mutations.add(lys.type)

					// add some confs
					confs.addAll(ala)
					confs.addAll(leu, "pp", "tp", "tt")
					confs.addAll(lys, "ptpt", "tptm", "mttt")

					// add continuous degrees of freedom
					for (space in confs.getByFragment(ala)) {
						val settings = DihedralAngle.LibrarySettings(
							radiusDegrees = 5.0,
							includeHydroxyls = true,
							includeNonHydroxylHGroups = true
						)
						space.motions.addAll(DihedralAngle.ConfDescription.makeFromLibrary(pos1, space.frag, space.conf, settings))
					}
					for (frag in listOf(leu, lys)) {
						val settings = DihedralAngle.LibrarySettings(
							radiusDegrees = 9.0,
							includeHydroxyls = false,
							includeNonHydroxylHGroups = false
						)
						for (space in confs.getByFragment(frag)) {
							space.motions.addAll(DihedralAngle.ConfDescription.makeFromLibrary(pos1, space.frag, space.conf, settings))
						}
					}
				}
			}
			val compiledConfSpace = confSpace.compile()

			testConf(compiledConfSpace, "ALA:ALA") {

				// make sure we got the right dofs
				dofs.size shouldBe 1
				dofs[0].shouldBeTypeOf<CompiledDihedralAngle.Dof> { angle ->
					(angle.max() - angle.min()).toDegrees() shouldBe 10.0.absolutely(1e-9)
				}

				// we shouldn't have static atom pairs
				getIndices(0).size() shouldBe 0

				// check minimized energy
				(-48.01726089618421).let {
					(calcAmber96() + calcEEF1()).shouldBeEnergy(it)
					calcEnergy().shouldBeEnergy(it)
					minimizeEnergy() shouldBeLessThan it
				}
			}

			testConf(compiledConfSpace, "LEU:pp") {

				// make sure we got the right dofs
				dofs.size shouldBe 2
				for (i in 0 until 2) {
					dofs[i].shouldBeTypeOf<CompiledDihedralAngle.Dof> { angle ->
						(angle.max() - angle.min()).toDegrees() shouldBe 18.0.absolutely(1e-9)
					}
				}

				// we shouldn't have static atom pairs
				getIndices(0).size() shouldBe 0

				// check minimized energy
				(-44.76305148873534).let {
					(calcAmber96() + calcEEF1()).shouldBeEnergy(it)
					calcEnergy().shouldBeEnergy(it)
					minimizeEnergy() shouldBeLessThan it
				}
			}

			testConf(compiledConfSpace, "LEU:tt") {

				// make sure we got the right dofs
				dofs.size shouldBe 2
				for (i in 0 until 2) {
					dofs[i].shouldBeTypeOf<CompiledDihedralAngle.Dof> { angle ->
						(angle.max() - angle.min()).toDegrees() shouldBe 18.0.absolutely(1e-9)
					}
				}

				// we shouldn't have static atom pairs
				getIndices(0).size() shouldBe 0

				// check minimized energy
				(-24.801305933011164).let {
					(calcAmber96() + calcEEF1()).shouldBeEnergy(it)
					calcEnergy().shouldBeEnergy(it)
					minimizeEnergy() shouldBeLessThan it
				}
			}

			testConf(compiledConfSpace, "LYS:ptpt") {

				// make sure we got the right dofs
				dofs.size shouldBe 4
				for (i in 0 until 4) {
					dofs[i].shouldBeTypeOf<CompiledDihedralAngle.Dof> { angle ->
						(angle.max() - angle.min()).toDegrees() shouldBe 18.0.absolutely(1e-9)
					}
				}

				// we shouldn't have static atom pairs
				getIndices(0).size() shouldBe 0

				// check minimized energy
				(-64.30395031713154).let {
					(calcAmber96() + calcEEF1()).shouldBeEnergy(it)
					calcEnergy().shouldBeEnergy(it)
					minimizeEnergy() shouldBeLessThan it
				}
			}
		}

		group("no positions, molecule dihedral") {

			val mol = loadMol()
			val res72 = mol.findChainOrThrow("A").findResidueOrThrow("72")

			val confSpace = ConfSpace(listOf(MoleculeType.Protein to mol)).apply {

				// make a dihedral on the N-terminal cap
				val motions = molMotions.getOrPut(mol) { ArrayList() }
				motions.add(DihedralAngle.MolDescription.make(
					mol,
					res72.findAtomOrThrow("C"),
					res72.findAtomOrThrow("CA"),
					res72.findAtomOrThrow("N"),
					res72.findAtomOrThrow("H1"),
					9.0
				))
			}
			val compiledConfSpace = confSpace.compile()

			testConf(compiledConfSpace) {

				// make sure we got the right dofs
				dofs.size shouldBe 1
				dofs[0].shouldBeTypeOf<CompiledDihedralAngle.Dof> { angle ->
					(angle.max() - angle.min()).toDegrees() shouldBe 18.0.absolutely(1e-9)
				}

				// we should have static atom pairs too
				getIndices(0).size() shouldBeGreaterThan 0

				// check minimized energy
				(-50.6633531679957).let {
					(calcAmber96() + calcEEF1()).shouldBeEnergy(it)
					calcEnergy().shouldBeEnergy(it)
					minimizeEnergy() shouldBeLessThan it
				}
			}
		}
	}
})
