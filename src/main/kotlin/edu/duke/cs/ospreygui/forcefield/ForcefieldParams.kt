package edu.duke.cs.ospreygui.forcefield

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.toIdentitySet
import java.util.*


typealias FfparamsPairFunc = (mola: Molecule, atoma: Atom, molb: Molecule, atomb: Atom, distance: Int?) -> Unit

interface ForcefieldParams {

	val forcefield: Forcefield

	/**
	 * Let the parameterizer know what molecules we want to parameterize next
	 */
	fun setMolecules(mols: List<Molecule>, smallMolNetCharges: Map<Molecule,Int>)

	/**
	 * Return the internal energy for this atom, if any.
	 */
	fun internalEnergy(mol: Molecule, atom: Atom): Double?

	/**
	 * Return the forcefield parameters for this atom pair interaction, if any.
	 */
	fun pairParams(mola: Molecule, atoma: Atom, molb: Molecule, atomb: Atom, dist: Int?): ParamsList?

	/**
	 * Calculate the forcefield energy of the selected atoms directly, rather than
	 * collect the forcefield parameters. eg, for the fixed atoms.
	 *
	 * Not called a whole lot, doesn't need to be fast.
	 */
	fun calcEnergy(atomsByMol: Map<Molecule,List<Atom>>): Double


	/**
	 * An opaque type a forcefield can use to determine when
	 * conformation changes cause changes in forcefield parameters.
	 */
	interface Analysis {
		fun findChangedAtoms(originalAnalysis: Analysis): Set<Atom>
	}
	fun analyze(atomsByMol: Map<Molecule,Set<Atom>>): Analysis


	interface ParamsList {
		val list: List<Double>
	}

	class AtomParams<T> {

		private val map = IdentityHashMap<Molecule,IdentityHashMap<Atom,T>>()

		operator fun get(mol: Molecule, atom: Atom): T? =
			map
				.get(mol)
				?.get(atom)

		operator fun set(mol: Molecule, atom: Atom, value: T): T? =
			map
				.getOrPut(mol) { IdentityHashMap() }
				.put(atom, value)
	}

	// TODO: atom pairs? for amber?

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
						func(mol, atoma, mol, atomb, dist)
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
