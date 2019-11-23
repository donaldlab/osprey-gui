package edu.duke.cs.ospreygui.forcefield

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.AtomPair
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.toIdentitySet
import java.util.*


typealias FfparamsPairFunc = (mola: Molecule, atoma: Atom, molb: Molecule, atomb: Atom, distance: Int?) -> Unit

interface ForcefieldParams {

	val forcefield: Forcefield

	/**
	 * Any settings needed by the Osprey runtime to calculate this forcefield.
	 */
	fun settings(): Map<String,Any> = emptyMap()

	/**
	 * An opaque type the parameterizer can use to store parameters for a molecule.
	 */
	abstract class MolParams(
		val mol: Molecule
	) {

		/**
		 * Useful for mapping atoms across molecules efficiently.
		 */
		val atomsLookup = mol.atoms.associateWith { it }

		abstract fun isChanged(thisAtom: Atom, baseline: MolParams, baseAtom: Atom): Boolean

		/**
		 * Return the atoms in this molecule whose params differ from the atoms in the baseline params.
		 */
		fun findChangedAtoms(baseline: MolParams, atoms: Set<Atom>): Set<Atom> {

			// make it easy to find atoms in the baseline
			val baseAtoms = baseline.mol.atoms.associateWith { it }

			return atoms
				.filter { atom ->

					// map the atom to our params molecule
					val thisAtom = findAtom(atom)

					// match to the atom in the baseline
					val baseAtom = baseAtoms[thisAtom]
						?: throw NoSuchElementException("Atom not found in baseline: $thisAtom")

					// did any params change?
					isChanged(thisAtom, baseline, baseAtom)
				}
				.toIdentitySet()
		}

		/**
		 * Find the atoms in this molecule that correspond to the given atoms,
		 * assuming the given atoms come from a different copy of this molecule.
		 */
		fun findAtom(otherAtom: Atom): Atom {
			return atomsLookup[otherAtom]
				?: throw NoSuchElementException("no matching atom found in this mol like $otherAtom")
		}
	}

	/**
	 * Compute the forcefield parameters for a molecule.
	 */
	fun parameterize(mol: Molecule, netCharge: Int?): MolParams


	interface ParamsList {
		val list: List<Double>
	}

	/**
	 * Return the internal energy for this atom, if any.
	 *
	 * `atom` might not be part of the molecule in `molParams`.
	 */
	fun internalEnergy(molParams: MolParams, atom: Atom): Double?

	/**
	 * Return the forcefield parameters for this atom pair interaction, if any.
	 *
	 * `atoma` might not be part of the molecule in `molaParams`.
	 * `atomb` might not be part of the molecule in `molbParams`.
	 */
	fun pairParams(molaParams: MolParams, atoma: Atom, molbParams: MolParams, atomb: Atom, dist: Int?): ParamsList?

	/**
	 * Calculate the forcefield energy of the selected atoms directly, rather than
	 * collect the forcefield parameters. eg, for the fixed atoms.
	 *
	 * Not called a whole lot, doesn't need to be fast.
	 *
	 * The atoms in each list might not be a member of the molecule in the associated mol params.
	 */
	fun calcEnergy(atomsByMols: Map<Molecule,List<Atom>>, molParamsByMols: Map<Molecule,MolParams>): Double


	companion object {

		fun forEachPair(atomsByMola: Map<Molecule,List<Atom>>, atomsByMolb: Map<Molecule,List<Atom>>, func: FfparamsPairFunc) {
			for ((mola, atomsa) in atomsByMola) {
				for ((molb, atomsb) in atomsByMolb) {
					if (mola === molb) {
						forEachPairIntramol(mola, atomsa, atomsb, func)
					} else {
						forEachPairIntermol(mola, atomsa, molb, atomsb, func)
					}
				}
			}
		}

		fun forEachPairIntramol(mol: Molecule, atomsa: List<Atom>, atomsb: List<Atom>, func: FfparamsPairFunc) {

			val lookupa = atomsa.toIdentitySet()
			val lookupb = atomsb.toIdentitySet()
			val visitedPairs = HashSet<AtomPair>()

			for (atoma in atomsa) {

				// pair energies, by bonded distance
				mol
					.bfs(
						source = atoma,
						visitSource = false,
						shouldVisit = { _, toAtom, dist -> toAtom in lookupa || toAtom in lookupb }
					)
					.filter { (atomb, _) ->
						atomb in lookupb
					}
					.forEach { (atomb, dist) ->
						// make sure we only visit each pair of atoms once though
						val wasAdded = visitedPairs.add(AtomPair(atoma, atomb))
						if (wasAdded) {
							func(mol, atoma, mol, atomb, dist)
						}
					}
			}
		}

		fun forEachPairIntermol(mola: Molecule, atomsa: List<Atom>, molb: Molecule, atomsb: List<Atom>, func: FfparamsPairFunc) {

			// easy peasy, all atom pairs are nonbonded
			for (atoma in atomsa) {
				for (atomb in atomsb) {
					func(mola, atoma, molb, atomb, null)
				}
			}
		}
	}
}
