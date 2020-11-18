package edu.duke.cs.ospreygui.motions

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.AtomMap
import edu.duke.cs.molscope.molecule.Molecule


/**
 * Manipulates a molecule with continuous motions.
 */
interface MolMotion {

	/**
	 * Describes a continuous motion on a molecule.
	 */
	interface Description {

		val mol: Molecule
		fun copyTo(mol: Molecule, atomMap: AtomMap = this.mol.mapAtomsByValueTo(mol)): Description

		fun make(): MolMotion

		/** Returns atoms whose positions are modified by the motion. */
		fun getAffectedAtoms(): List<Atom>
	}
}
