package edu.duke.cs.ospreygui.forcefield.eef1

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.tools.identityHashMapOf
import edu.duke.cs.osprey.confspace.ParametricMolecule
import edu.duke.cs.osprey.confspace.Strand
import edu.duke.cs.osprey.energy.EnergyCalculator
import edu.duke.cs.osprey.energy.ResidueInteractions
import edu.duke.cs.osprey.energy.forcefield.ForcefieldParams
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.io.fromOMOL
import edu.duke.cs.ospreygui.io.toOspreyMol
import io.kotlintest.matchers.doubles.plusOrMinus
import io.kotlintest.shouldBe


class TestEnergy : SharedSpec({

	/**
	 * Calculate the molecule energy using the new parameterization system.
	 */
	fun Molecule.calcEnergyParameterized(): Double {

		// parameterize the molecule
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

	fun readMol(name: String) =
		Molecule.fromOMOL(OspreyGui.getResourceAsString("preppedMols/$name.omol"))[0]

	group("compared to templated ecalc") {

		/**
		 * The parameters from the newer code are identical to the older code,
		 * so the energies should match very precisely.
		 */
		fun Double.shouldBeEnergy(expected: Double, epsilon: Double = 0.9) {
			this shouldBe expected.plusOrMinus(epsilon)
		}

		/**
		 * Calculate the molecule energy using osprey's current template-based energy calculator
		 */
		fun Molecule.calcEnergyTemplated(): Double {

			// match the molecule to osprey's templates
			val tmol = Strand.Builder(this.toOspreyMol()).build().mol

			// convert to a parametric molecule with no motions
			val pmol = ParametricMolecule(tmol)

			// use a complete residue interaction graph
			val inters = ResidueInteractions().apply {
				addComplete(tmol.residues)
			}

			// sadly, old-school osprey can't calculate *just* the EEF1 energy
			// so we'll compute amber + EEF1 energy, then subtract off the amber energy

			// calculate the amber + EEF1 energy
			val combinedEnergy = run {
				val ffparams = ForcefieldParams().apply {
					solvationForcefield = ForcefieldParams.SolvationForcefield.EEF1
					solvScale = 1.0
				}
				EnergyCalculator.Builder(ffparams).build().use { ecalc ->
					return@run ecalc.calcEnergy(pmol, inters).energy
				}
			}

			// calculate the amber energy
			val amberEnergy = run {
				val ffparams = ForcefieldParams().apply {
					solvationForcefield = null
				}
				EnergyCalculator.Builder(ffparams).build().use { ecalc ->
					return@run ecalc.calcEnergy(pmol, inters).energy
				}
			}

			return combinedEnergy - amberEnergy
		}

		test("gly-gly") {
			val mol = readMol("gly-gly")
			mol.calcEnergyParameterized().shouldBeEnergy(mol.calcEnergyTemplated())
		}

		test("trp-trp") {
			val mol = readMol("trp-trp")
			mol.calcEnergyParameterized().shouldBeEnergy(mol.calcEnergyTemplated())
		}

		test("ser-met") {
			val mol = readMol("ser-met")
			mol.calcEnergyParameterized().shouldBeEnergy(mol.calcEnergyTemplated())
		}

		test("1cc8") {
			val mol = Molecule.fromOMOL(OspreyGui.getResourceAsString("1cc8.protein.omol"))[0]
			mol.calcEnergyParameterized().shouldBeEnergy(mol.calcEnergyTemplated())
		}
	}

	/*
		These tests are designed to catch regressions in the EEF1 energy calculator.
		The expected values were captured when the code was last deemed to be working correctly.
	*/
	group("precision regressions") {

		fun Double.shouldBeEnergy(expected: Double, epsilon: Double = 1e-9) {
			this shouldBe expected.plusOrMinus(epsilon)
		}

		test("gly-gly") {
			readMol("gly-gly").calcEnergyParameterized().shouldBeEnergy(-47.75509989567506)
		}

		test("trp-trp") {
			readMol("trp-trp").calcEnergyParameterized().shouldBeEnergy(-55.55169383524431)
		}

		test("ser-met") {
			readMol("ser-met").calcEnergyParameterized().shouldBeEnergy(-50.90501284136588)
		}

		test("1cc8") {
			val mol = Molecule.fromOMOL(OspreyGui.getResourceAsString("1cc8.protein.omol"))[0]
			mol.calcEnergyParameterized().shouldBeEnergy(-691.5469503851598)
		}
	}
})
