package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.SharedSpec
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.forcefield.eef1.EEF1ForcefieldParams
import edu.duke.cs.ospreygui.prep.ConfSpace
import io.kotlintest.matchers.doubles.plusOrMinus
import edu.duke.cs.osprey.confspace.compiled.ConfSpace as CompiledConfSpace
import io.kotlintest.shouldBe


class TestConfSpaceCompiler : SharedSpec({

	/**
	 * The compiled conf space only uses six digits of precision,
	 * so an epsilon of 1e-6 should work.
	 */
	fun Double.shouldBeEnergy(expected: Double, epsilon: Double = 1e-6) {
		this shouldBe expected.plusOrMinus(epsilon)
	}

	// this essentially tests the static energy calculation
	group("1cc8 no positions") {

		val mol = Molecule.fromOMOL(OspreyGui.getResourceAsString("1cc8.protein.omol.toml"))[0] as Polymer
		val confSpace = ConfSpace(listOf(MoleculeType.Protein to mol))

		fun ConfSpace.compileAndCalcEnergy(forcefield: Forcefield, configurator: ForcefieldParams.() -> Unit = {}): Double {

			// compile the conf space
			val toml = ConfSpaceCompiler(this).run {

				addForcefield(forcefield).configurator()

				compile().run {
					errors.size shouldBe 0
					toml
				}
			}

			// send it to osprey to calculate the energy
			CompiledConfSpace(toml).run {
				val coords = assign(intArrayOf())
				return ecalcs[0].calcEnergy(coords)
			}
		}

		test("amber") {
			confSpace
				.compileAndCalcEnergy(Forcefield.Amber96)
				.shouldBeEnergy(-489.08432295295387)
		}

		test("eef1") {
			confSpace
				.compileAndCalcEnergy(Forcefield.EEF1) {
					this as EEF1ForcefieldParams
					// use the full scale for testing
					scale = 1.0
				}
				.shouldBeEnergy(-691.5469503851598)
		}
	}
})
