package edu.duke.cs.ospreygui.compiler

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.forcefield.amber.findTypeOrThrow
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.prep.DesignPosition
import java.util.*


/**
 * A place to store net charges for molecules and their conformations.
 * Net charges are needed by Antechamber/Sqm to compute partial charges for atoms.
 */
class NetCharges {

	private val smallMolNetCharges: MutableMap<Molecule,MolNetCharges> = IdentityHashMap()

	operator fun get(mol: Molecule): MolNetCharges? =
		when (mol.findTypeOrThrow()) {

			MoleculeType.SmallMolecule -> {
				// small molecule, need net charges
				smallMolNetCharges[mol]
					?: throw NoSuchElementException("Small molecule $mol has no net charges configured")
			}

			// not a small molecule, no net charges needed
			else -> null
		}
}

/**
 * Net charges for a single molecule and its conformations.
 */
class MolNetCharges(val mol: Molecule) {

	/** The net charge of the unmodified molecule */
	var netCharge: Int? = null

	val netChargeOrThrow get() =
		netCharge ?: throw IllegalStateException("molecule $mol needs a net charge")

	private val charges = IdentityHashMap<DesignPosition, IdentityHashMap<ConfLib.Fragment,Int>>()

	operator fun get(pos: DesignPosition, frag: ConfLib.Fragment) =
		charges.getOrPut(pos) { IdentityHashMap() }.get(frag)

	fun getOrThrow(pos: DesignPosition, frag: ConfLib.Fragment): Int =
		this[pos, frag]
			?: throw NoSuchElementException("molecule $mol needs a net charge for fragment ${frag.id} at design position ${pos.name}")

	operator fun set(pos: DesignPosition, frag: ConfLib.Fragment, charge: Int?) {
		charges.getOrPut(pos) { IdentityHashMap() }.let {
			if (charge != null) {
				it[frag] = charge
			} else {
				it.remove(frag)
			}
		}
	}
}
