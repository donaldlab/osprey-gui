package edu.duke.cs.ospreygui.motions

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.prep.DesignPosition


/**
 * Manipulates a conformation with a continuous motion.
 */
interface ConfMotion {

	/**
	 * Describes a continuous motion on a conformation.
	 */
	interface Description {
		fun copyTo(pos: DesignPosition): Description
		fun make(mol: Molecule, atomResolver: ConfLib.AtomPointer.Resolver): ConfMotion
	}
}
