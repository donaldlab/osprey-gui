package edu.duke.cs.ospreygui.forcefield

import edu.duke.cs.ospreygui.forcefield.amber.Amber14SBParams
import edu.duke.cs.ospreygui.forcefield.amber.Amber96Params
import edu.duke.cs.ospreygui.forcefield.eef1.EEF1ForcefieldParams


// sort of like an enum, but with specialized type info for each enum value
interface Forcefield {

	val name: String get() = javaClass.simpleName

	val ospreyImplementation: String
	fun parameterizer(): ForcefieldParams

	/**
	 * Time-tested protein forcefield and historical favorite of the Donald Lab.
	 */
	object Amber96 : Forcefield {

		override val ospreyImplementation = "amber"
		override fun parameterizer() = Amber96Params()

		fun configure(block: Amber96Params.() -> Unit) =
			Amber96Params().apply { block() }
	}

	/**
	 * Currently recommended by Amber 19 for simulation of proteins.
	 */
	object Amber14SB : Forcefield {

		override val ospreyImplementation = "amber"
		override fun parameterizer() = Amber14SBParams()

		fun configure(block: Amber14SBParams.() -> Unit) =
			Amber14SBParams().apply { block() }
	}

	object EEF1 : Forcefield {

		override val ospreyImplementation = "eef1"
		override fun parameterizer() = EEF1ForcefieldParams()

		fun configure(block: EEF1ForcefieldParams.() -> Unit) =
			EEF1ForcefieldParams().apply { block() }
	}
}
