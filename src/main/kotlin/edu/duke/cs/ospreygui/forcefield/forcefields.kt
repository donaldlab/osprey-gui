package edu.duke.cs.ospreygui.forcefield

import edu.duke.cs.ospreygui.forcefield.amber.Amber14SBConfSpaceParams
import edu.duke.cs.ospreygui.forcefield.amber.Amber96ConfSpaceParams
import edu.duke.cs.ospreygui.forcefield.eef1.EEF1ConfSpaceParams


enum class Forcefield {

	/**
	 * Time-tested protein forecfield and historical favorite of the Donald Lab.
	 */
	Amber96 {
		override fun parameterizer() = Amber96ConfSpaceParams()
	},

	/**
	 * Currently recommended by Amber 19 for simulation of proteins.
	 */
	Amber14SB {
		override fun parameterizer() = Amber14SBConfSpaceParams()
	},

	EEF1 {
		override fun parameterizer() = EEF1ConfSpaceParams()
	};

	abstract fun parameterizer(): ForcefieldParams
}
