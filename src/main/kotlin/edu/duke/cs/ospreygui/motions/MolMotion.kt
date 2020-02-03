package edu.duke.cs.ospreygui.motions

import edu.duke.cs.molscope.molecule.Molecule


/**
 * Manipulates a molecule with continuous motions.
 */
interface MolMotion {

	/**
	 * Describes a continuous motion on a molecule.
	 */
	interface Description {
		fun copyTo(mol: Molecule): Description
		fun make(): MolMotion
	}
}
