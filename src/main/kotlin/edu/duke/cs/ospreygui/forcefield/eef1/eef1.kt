package edu.duke.cs.ospreygui.forcefield.eef1

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule


object EEF1 {

	/**
	 * Atom types as defined in the paper:
	 * Effective Energy Function for Proteins in Solution
	 * Themis Lazaridis and Martin Karplus
	 * PROTEINS 1999
	 */
	enum class AtomType(
		val volume: Double,
		val dGref: Double,
		val dGfree: Double,
		val dHref: Double,
		val dCpref: Double,
		val lambda: Double,
		val vdwRadius: Double
	) {

		/** carbonyl carbon */
		C(    14.7,     0.0,    0.0,     0.0,    0.0, 3.5,    2.1),
		/** carbon with no hydrogens */
		CR(    8.3,   -0.89,   -1.4,    2.22,    6.9, 3.5,    2.1),
		/** extended aromatic carbon with 1 */
		CR1E( 23.7,  -0.187,  -0.25,   0.876,    0.0, 3.5,    2.1),
		/** extended aliphatic carbon with 1 H */
		CH1E( 22.4,   0.372,   0.52,   -0.61,   18.6, 3.5,  2.365),
		/** extended aliphatic carbon with 2 H */
		CH2E( 30.0,   1.089,    1.5,  -1.779,   35.6, 3.5,  2.235),
		/** extended aliphatic carbon with 3 H */
		CH3E( 18.4,   0.057,   0.08,  -0.973,    6.9, 3.5,  2.165),
		/** amide nitrogen */
		NH1(   4.4,   -5.95,   -8.9,  -9.059,   -8.8, 3.5,    1.6),
		/** aromatic nitrogen with no hydrogens */
		NR(    4.4,   -3.82,   -4.0,  -4.654,   -8.8, 3.5,    1.6),
		/** nitrogen bound to two hydrogens */
		NH2(  11.2,   -5.45,   -7.8,  -9.028,   -7.0, 3.5,    1.6),
		/** nitrogen bound to three hydrogens */
		NH3(  11.2,   -20.0,  -20.0,   -25.0,  -18.0, 6.0,    1.6),
		/** guanidinium nitrogen */
		NC2(  11.2,   -10.0,  -10.0,   -12.0,   -7.0, 6.0,    1.6),
		/** proline nitrogen */
		N(     0.0,    -1.0,  -1.55,   -1.25,    8.8, 3.5,    1.6),
		/** hydroxyl oxygen */
		OH1(   10.8,  -5.92,   -6.7,  -9.264,  -11.2, 3.5,    1.6),
		/** carbonyl oxygen */
		O(     10.8,  -5.33,  -5.85,  -5.787,   -8.8, 3.5,    1.6),
		/** carboxyl oxygen */
		OC(    10.8,  -10.0,  -10.0,   -12.0,   -9.4, 6.0,    1.6),
		/** sulphur */
		S(     14.7,  -3.24,   -4.1,  -4.475,  -39.9, 3.5,   1.89),
		/** extended sulphur with one hydrogen */
		SH1E(  21.4,  -2.05,   -2.7,  -4.475,  -39.9, 3.5,   1.89);


		companion object {

			fun get(mol: Molecule, atom: Atom): AtomType? {

				val numH = mol.bonds.bondedAtoms(atom)
					.count { it.element == Element.Hydrogen }
				val numHeavy = mol.bonds.bondedAtoms(atom)
					.count { it.element != Element.Hydrogen }

				return when (atom.element) {

					Element.Carbon -> when (numH) {
						3 -> CH3E
						2 -> CH2E
						1 -> when (numHeavy) {
							3 -> CH1E
							2 -> CR1E
							else -> null
						}
						0 -> if (mol.bonds.bondedAtoms(atom).any { it.element == Element.Oxygen }) {
							C
						} else {
							CR
						}
						else -> null
					}

					Element.Nitrogen -> when (numH) {
						3 -> NH3
						2 ->
							// is this a guanidinium NH2 or a regular NH2?
							// look at the attached C, if any
							mol.bonds.bondedAtoms(atom)
								.filter { it.element == Element.Carbon }
								.takeIf { it.size == 1 }
								?.get(0)
								?.let { c ->
									when (mol.bonds.bondedAtoms(c).count { it.element == Element.Nitrogen }) {
										3 -> NC2
										else -> NH2
									}
								}
								?: NH2
						1 -> NH1
						0 -> when (numHeavy) {
							3 -> N
							2 -> NR
							else -> null
						}
						else -> null
					}

					Element.Oxygen -> when (numH) {
						1 -> OH1
						0 ->
							// is this carbonyl or carboxyl oxygen?
							// look at the attached C, if any
							mol.bonds.bondedAtoms(atom)
								.filter { it.element == Element.Carbon }
								.takeIf { it.size == 1 }
								?.get(0)
								?.let { c ->
									when (mol.bonds.bondedAtoms(c).count { it.element == Element.Oxygen }) {
										2 -> OC
										1 -> O
										else -> null
									}
								}
						else -> null
					}

					Element.Sulfur -> when (numH) {
						1 -> SH1E
						0 -> S
						else -> null
					}

					else -> null
				}
			}

			fun getOrThrow(mol: Molecule, atom: Atom): AtomType =
				get(mol, atom) ?: throw NoSuchElementException("no EEF1 atom type defined for $atom")
		}
	}
}

fun Atom.atomTypeEEF1(mol: Molecule) =
	EEF1.AtomType.get(mol, this)

fun Atom.atomTypeEEF1OrThrow(mol: Molecule) =
	EEF1.AtomType.getOrThrow(mol, this)
