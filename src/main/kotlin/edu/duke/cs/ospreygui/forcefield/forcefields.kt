package edu.duke.cs.ospreygui.forcefield

import edu.duke.cs.ospreygui.forcefield.eef1.EEF1ConfSpaceParams


enum class Forcefield {

	/* TODO
	Amber96 {
		override fun parameterize(confSpace: ConfSpace) =
			Amber96ConfSpaceParams(confSpace)
	},
	*/
	EEF1 {
		override fun parameterizer() = EEF1ConfSpaceParams()
	};

	abstract fun parameterizer(): ForcefieldParams
}
