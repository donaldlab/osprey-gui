package edu.duke.cs.ospreygui.forcefield.amber

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
	 * Calculate the molecule energy using osprey's current template-based energy calculator
	 */
	fun Molecule.calcEnergyTemplated(): Double {

		// match the molecule to osprey's templates
		val tmol = Strand.Builder(this.toOspreyMol()).build().mol

		// convert to a parametric molecule with no DoFs
		val pmol = ParametricMolecule(tmol)

		// use a complete residue interaction graph
		val inters = ResidueInteractions().apply {
			addComplete(tmol.residues)
		}

		// build the energy calculator with just the amber forcefield
		val ffparams = ForcefieldParams().apply {
			solvationForcefield = null
		}
		EnergyCalculator.Builder(ffparams).build().use { ecalc ->

			// finally, calculate the energy
			return ecalc.calcEnergy(pmol, inters).energy
		}
	}

	/**
	 * Calculate the molecule energy using the new parameterization system.
	 */
	fun Molecule.calcEnergyParameterized(): Double {

		// parameterize the molecule
		val amberParams = Amber96Params()
		val molParams = amberParams.parameterize(this, null)

		return amberParams.calcEnergy(
			identityHashMapOf(this to atoms),
			identityHashMapOf(this to molParams)
		)
	}

	/**
	 * The parameters from the newer code are a bit more precise than the older code,
	 * so the new energy values don't exactly match the old ones.
	 * A tolerance of 0.1 seems to cover the gap though.
	 */
	fun Double.shouldBeEnergy(expected: Double, epsilon: Double = 0.1) {
		this shouldBe expected.plusOrMinus(epsilon)
	}

	fun readMol(name: String) =
		Molecule.fromOMOL(OspreyGui.getResourceAsString("preppedMols/$name.omol.toml"))[0]

	test("gly-gly") {
		val mol = readMol("gly-gly")
		mol.calcEnergyParameterized().shouldBeEnergy(mol.calcEnergyTemplated())
	}

	test("1cc8") {
		val mol = Molecule.fromOMOL(OspreyGui.getResourceAsString("1cc8.protein.omol.toml"))[0]
		mol.calcEnergyParameterized().shouldBeEnergy(mol.calcEnergyTemplated())
	}
})
