package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.toIdentitySet
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import java.util.*



class Amber96ConfSpaceParams : AmberConfSpaceParams(mapOf(
	MoleculeType.Protein to ForcefieldName.ff96
)) {
	override val forcefield = Forcefield.Amber96
}

class Amber14SBConfSpaceParams : AmberConfSpaceParams(mapOf(
	MoleculeType.Protein to ForcefieldName.ff14SB
)) {
	override val forcefield = Forcefield.Amber14SB
}

abstract class AmberConfSpaceParams(val ffnameOverrides: Map<MoleculeType,ForcefieldName>) : ForcefieldParams {

	/**
	 * The dielectric constant of the environment (aka its relative permittivity),
	 * which influences electrostatic calculations.
	 *
	 * This appears to be a magic number (for now).
	 * Maybe 6 is reasonable for a protein interior?
	 *
	 * For reference, this value seems to have a simlar relative permittivity
	 * to neoperene (6.7), but is far less than water (~80).
	 * See: https://en.wikipedia.org/wiki/Relative_permittivity
	 */
	var dielectric = 6.0

	/**
	 * If true, multiply the dielectric contstant by the atom pair distance (r)
	 * for electrostatic interactions.
	 */
	var distanceDependentDielectric = true

	/**
	 * Scaling to apply to the van der Waals calculations.
	 *
	 * The default value of 0.95 was determined empirically by Osprey developers
	 * to avoid specific issues caused by extreme van der Waals repulsion.
	 */
	var vdwScale = 0.95

	/**
	 * Method to generate partial charges for small molecules.
	 *
	 * The default AM1BCC method is currently recommended by Amber for most purposes.
	 */
	var chargeMethod = Antechamber.ChargeMethod.AM1BCC

	// TODO: expose the above options for configuration somewhere?


	private var topology: AmberTopology.Mapped? = null
	private val topologyOrThrow get() =
		topology ?: throw IllegalStateException("call setMolecules() before calling other methods")


	private data class TypesKey(val mol: String, val netCharge: Int?) {

		companion object {

			// TODO: shouldn't this really be some kind of molecule equals() function??
			//   then we could just make the Molecule itself part of the key rather than this string
			/**
			 * Render the molecule into a string we can use as a hash table key.
			 * The key should uniquely describe the molecule, but be insensitive
			 * to nuisance factors like atom or bond orders.
			 */
			private fun Molecule.toKeyString(): String {
				val buf = StringBuilder()

				// sort the atoms and positions
				val sortedAtoms = atoms
					.sortedBy { it.pos.z }
					.sortedBy { it.pos.y }
					.sortedBy { it.pos.x }
					.sortedBy { it.name }
				val atomIndices = sortedAtoms
					.mapIndexed { i, atom -> atom to i }
					.associate { (atom, i) -> atom to i }

				// write the atoms and positions
				sortedAtoms.forEach {
					buf.append("${it.name}_${it.pos.x}_${it.pos.y}_${it.pos.z}\n")
				}

				// sort the bonds
				data class Bond(val i1: Int, val i2: Int)
				val sortedBonds = atoms
					.flatMap { a1 ->
						bonds.bondedAtoms(a1).map { a2 ->
							Bond(atomIndices.getValue(a1), atomIndices.getValue(a2))
						}
					}
					.sortedBy { it.i2 }
					.sortedBy { it.i1 }

				// write out the bonds
				sortedBonds.forEach {
					buf.append("${it.i1}_${it.i2}\n")
				}

				return buf.toString()
			}
		}

		constructor(mol: Molecule, netCharge: Int?) : this(mol.toKeyString(), netCharge)
	}
	private val typesCache = HashMap<TypesKey,AmberTypes>()

	override fun setMolecules(mols: List<Molecule>, smallMolNetCharges: Map<Molecule,Int>) {

		// calc all the amber types
		val molsAndTypes = mols.map { mol ->

			// find the forcefield name
			val type = mol.findTypeOrThrow()
			val ffname = ffnameOverrides[type] ?: type.defaultForcefieldNameOrThrow

			// get charge generation settings if small molecule
			val molType = mol.findTypeOrThrow()
			val (chargeMethod, netCharge) = when (molType) {
				MoleculeType.SmallMolecule -> {
					chargeMethod to (smallMolNetCharges[mol]
						?: throw IllegalStateException("No net charge set for small molecule: $mol"))
				}
				else -> null to null
			}

			// get the amber types
			try {

				/* NOTE:
					This function gets called several times for each conformation single and pair.
					Computing Amber types can be somewhat slow, especially if we have to call
					antechamber (and sqm) for a small molecule.
					If there are multiple molecules, we'll end up parameterizing the exact same small molecule
					conformations several times while we iterate over conformations of other molecules.
					So, to speed things up, we can cache the amber types between instances of the exact
					same molecules, and re-use them across different conformations of the other molecules.
				*/

				// check the cache first
				val types = typesCache.getOrPut(TypesKey(mol, netCharge)) {

					// cache miss, run amber to calculate
					mol.calcTypesAmber(mol.findTypeOrThrow(), ffname, chargeMethod, netCharge)
				}

				mol to types

			} catch (ex: Antechamber.Exception) {

				fun List<Pair<SQM.ErrorType,String>>.format(): String {
					val buf = StringBuffer()
					for ((errType, errMsg) in this) {

						buf.append("\n")

						// show the error message
						errMsg
							.split("\n")
							.forEachIndexed { i, line ->
								if (i == 0) {
									buf.append(" * $line\n")
								} else {
									buf.append("   $line\n")
								}
							}

						// suggest potential fixes
						val suggestedFix = when (errType) {
							SQM.ErrorType.NoConvergence ->
								"Maybe try fixing issues with molecule chmesitry or structure?"
							SQM.ErrorType.BadNetCharge ->
								"Try changing the net charge for this conformation?"
						}
						buf.append("TO FIX: $suggestedFix\n")
					}
					return buf.toString()
				}

				// parameterizing small molecules is especially error-prone
				// since we need to call SQM to compute the partial charges
				// if SQM failed, try to give a friendly(er) error message to the user
				ex.results.sqm
					?.takeIf { it.errors.isNotEmpty() }
					?.let {
						throw RuntimeException("""
							|Can't generate partial charges for $mol
							|SQM failed with errors:
							|${it.errors.format()}
						""".trimMargin(), ex)
					}
					// if we got here, we didn't parse any errors from sqm, so just re-throw the original exception
					?: throw ex
			}
		}

		// calculate any frcmods we may need
		val frcmods = molsAndTypes.mapNotNull { (mol, types) ->
			mol.calcModsAmber(types)
		}

		// calculate the amber "topology" which has the real forcefield parameters in it
		val params = molsAndTypes.calcParamsAmber(frcmods)
		val top = TopIO.read(params.top)

		// read the amber forcefield params from the toplogy file
		topology = top.mapTo(mols)
	}

	/** Amber forcefields don't have internal energies */
	override fun internalEnergy(mol: Molecule, atom: Atom) = null

	inner class PairParams(
		val esQ: Double,
		val vdwA: Double,
		val vdwB: Double
	) : ForcefieldParams.ParamsList {

		// TODO: should we include torsion parameters?!

		override val list = listOf(
			esQ,
			vdwA,
			vdwB
		)

		/**
		 * See Amber manual, Eqn 14.1
		 * But we've modified the electrostatic calculations to use a
		 * distance-dependent dielectric (ie 1/r2 instead of 1/r) when needed.
		 */
		fun calcEnergy(r: Double): Double {

			// calculate the electrostatics energy
			val r2 = r*r
			val es = if (distanceDependentDielectric) {
				esQ/r2
			} else {
				esQ/r
			}

			// calculate the van der Waals energy
			val r6 = r2*r2*r2
			val r12 = r6*r6
			val vdw = vdwA/r12 - vdwB/r6

			return es + vdw
		}
	}

	// Amber forcefields only have "non-bonded" interactions between atoms at least 1-4 bonded (ie >= 3 bonds away)
	private fun inRange(dist: Int?) =
		dist == null || dist >= 3

	override fun pairParams(mola: Molecule, atoma: Atom, molb: Molecule, atomb: Atom, dist: Int?): PairParams? {

		if (!inRange(dist)) {
			return null
		}

		val top = topologyOrThrow

		// get the electrostatic params
		// NOTE: the coulomb factor (~322.05) is already pre-multiplied into the charges
		// see: Amber manual 14.1.7, Partial Charges
		var esQ = (top.charge(atoma) * top.charge(atomb))/dielectric

		// get the van der Waals params
		// NOTE: Lorentz/Berthelot mixing rules already pre-applied to A and B
		// see: Amber manual 14.1.7, Van der Waals Parameters
		var (vdwA, vdwB) = top.vdw(atoma, atomb)

		// apply the parametric vdW scaling
		vdwA *= vdwScale
		vdwB *= vdwScale

		// 1-4 bonded atoms have scaled interactions
		// see: Amber manual 14.1.6, 1-4 Non-Bonded Interaction Scaling
		if (dist == 3) {
			esQ /= top.chargeDivisor14(atoma, atomb)
			top.vdwDivisor14(atoma, atomb).let {
				vdwA /= it
				vdwB /= it
			}
		}

		return PairParams(esQ, vdwA, vdwB)
	}

	override fun calcEnergy(atomsByMol: Map<Molecule,List<Atom>>): Double {

		var energy = 0.0

		// add the pair energies
		ForcefieldParams.forEachPair(atomsByMol, atomsByMol) { mola, atoma, molb, atomb, dist ->
			pairParams(mola, atoma, molb, atomb, dist)?.let { params ->
				energy += params.calcEnergy(atoma.pos.distance(atomb.pos))
			}
		}

		return energy
	}

	class Analysis : ForcefieldParams.Analysis {

		data class AtomInfo(val charge: Double, val type: String)

		val infos = IdentityHashMap<Atom,AtomInfo>()

		override fun findChangedAtoms(originalAnalysis: ForcefieldParams.Analysis): Set<Atom> {
			originalAnalysis as Analysis
			return originalAnalysis.infos
				.filter { (atom, originalInfo) ->
					infos.getValue(atom) != originalInfo
				}
				.map { (atom, _) -> atom }
				.toIdentitySet()
		}
	}

	override fun analyze(atomsByMol: Map<Molecule,Set<Atom>>) = Analysis().apply {
		val top = topologyOrThrow
		for ((_, atoms) in atomsByMol) {
			for (atom in atoms) {
				infos[atom] = Analysis.AtomInfo(
					charge = top.charge(atom),
					type = top.atomType(atom)
				)
			}
		}
	}
}
