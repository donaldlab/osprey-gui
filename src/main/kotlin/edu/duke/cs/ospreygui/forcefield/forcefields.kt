package edu.duke.cs.ospreygui.forcefield

import edu.duke.cs.ospreygui.forcefield.amber.Amber14SBParams
import edu.duke.cs.ospreygui.forcefield.amber.Amber96Params
import edu.duke.cs.ospreygui.forcefield.eef1.EEF1ForcefieldParams


enum class Forcefield {

	/**
	 * Time-tested protein forecfield and historical favorite of the Donald Lab.
	 */
	Amber96 {
		override fun parameterizer() = Amber96Params()
	},

	/**
	 * Currently recommended by Amber 19 for simulation of proteins.
	 */
	Amber14SB {
		override fun parameterizer() = Amber14SBParams()
	},

	EEF1 {
		override fun parameterizer() = EEF1ForcefieldParams()
	};

	abstract fun parameterizer(): ForcefieldParams
}
