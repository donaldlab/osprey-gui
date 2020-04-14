package edu.duke.cs.ospreygui.forcefield

import edu.duke.cs.molscope.molecule.*
import edu.duke.cs.osprey.tools.HashCalculator
import java.util.*


typealias FfparamsPairFunc = (mola: Molecule, atoma: Atom, molb: Molecule, atomb: Atom, distance: Int?) -> Unit

interface ForcefieldParams {

	val forcefield: Forcefield

	/**
	 * Any settings needed by the Osprey runtime to calculate this forcefield.
	 */
	fun settings(): Map<String,Any> = emptyMap()

	/**
	 * An opaque tope the parameterizer can use to store parameters for an atom
	 */
	abstract class AtomParams(val atom: Atom)

	/**
	 * An opaque type the parameterizer can use to store parameters for a molecule.
	 */
	abstract class MolParams(val mol: Molecule) {

		/**
		 * Gets the atom params for this atom.
		 * The atom must be a member of the molecule in this params.
		 */
		abstract operator fun get(atom: Atom): AtomParams?
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
	 * `atom` must be part of the molecule in `molParams`.
	 */
	fun internalEnergy(molParams: MolParams, atom: Atom): Double?

	/**
	 * Return the forcefield parameters for this atom pair interaction, if any.
	 *
	 * `atoma` must be part of the molecule in `molaParams`.
	 * `atomb` must be part of the molecule in `molbParams`.
	 */
	fun pairParams(molaParams: MolParams, atoma: Atom, molbParams: MolParams, atomb: Atom, dist: Int?): ParamsList?

	/**
	 * Calculate the forcefield energy of the selected atoms directly, rather than
	 * collect the forcefield parameters. eg, for the fixed atoms.
	 *
	 * If the atoms are not members of the molecules in the  MolParams,
	 * then AtomMaps must be provided to perform the mapping to the MolParams molecules
	 *
	 * Not called a whole lot, doesn't need to be fast.
	 */
	fun calcEnergy(atomsByMols: Map<Molecule,List<Atom>>, molParamsByMols: Map<Molecule,MolParams>, molToParams: Map<Molecule,AtomMap>? = null): Double


	companion object {

		fun forEachPair(atomsByMola: Map<Molecule,List<Atom>>, atomsByMolb: Map<Molecule,List<Atom>>, func: FfparamsPairFunc) {

			// track molecule pairs, regardless of order
			class MolPair(val a: Molecule, val b: Molecule) {
				override fun hashCode() = HashCalculator.combineHashesCommutative(
					System.identityHashCode(a),
					System.identityHashCode(b)
				)
				override fun equals(other: Any?): Boolean = other is MolPair && (
					(this.a === other.a && this.b === other.b)
					|| (this.a === other.b && this.b === other.a)
				)
			}
			val molPairs = HashSet<MolPair>()

			for ((mola, atomsa) in atomsByMola) {
				for ((molb, atomsb) in atomsByMolb) {

					// make sure we only visit each pair of molecules once though
					val wasAdded = molPairs.add(MolPair(mola, molb))
					if (wasAdded) {

						if (mola === molb) {
							forEachPairIntramol(mola, atomsa, atomsb, func)
						} else {
							forEachPairIntermol(mola, atomsa, molb, atomsb, func)
						}
					}
				}
			}
		}

		fun forEachPairIntramol(mol: Molecule, atomsa: List<Atom>, atomsb: List<Atom>, func: FfparamsPairFunc) {

			val lookupb = atomsb.toIdentitySet()
			val visitedPairs = HashSet<AtomPair>()

			for (atoma in atomsa) {

				mol
					.bfs(
						source = atoma,
						visitSource = false,
						shouldVisit = { _, _, _ -> true }
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
