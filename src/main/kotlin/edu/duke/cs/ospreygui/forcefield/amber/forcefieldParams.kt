package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.AtomMap
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import java.util.*


class Amber96Params : AmberForcefieldParams(mapOf(
	MoleculeType.Protein to ForcefieldName.ff96
)) {
	override val forcefield = Forcefield.Amber96
}

class Amber14SBParams : AmberForcefieldParams(mapOf(
	MoleculeType.Protein to ForcefieldName.ff14SB
)) {
	override val forcefield = Forcefield.Amber14SB
}

abstract class AmberForcefieldParams(val ffnameOverrides: Map<MoleculeType,ForcefieldName>) : ForcefieldParams {

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

	override fun settings() = LinkedHashMap<String,Any>().apply {
		this["distanceDependentDielectric"] = distanceDependentDielectric
	}

	class AtomParams(
		atom: Atom,
		val type: String,
		val charge: Double
	) : ForcefieldParams.AtomParams(atom) {

		override fun hashCode() =
			type.hashCode()*31 + charge.hashCode()

		override fun equals(other: Any?) =
			other is AtomParams
				&& this.type == other.type
				&& this.charge == other.charge

		override fun toString() =
			"type=$type, charge=$charge"
	}

	class MolParams(
		mol: Molecule,
		val types: AmberTypes,
		val frcmod: String?
	) : ForcefieldParams.MolParams(mol) {

		override fun get(atom: Atom): ForcefieldParams.AtomParams? {
			val type = types.atomTypes[atom] ?: return null
			val charge = types.atomCharges[atom]?.toDoubleOrNull() ?: return null
			return AtomParams(atom, type, charge)
		}

		// NOTE: don't define hashCode() or equals() here
		// the topology cache depends on hash tables using this class
		// with identity comparisons rather than value comparisons
	}

	override fun parameterize(mol: Molecule, netCharge: Int?): MolParams {

		// find the forcefield name
		val molType = mol.findTypeOrThrow()
		val ffname = ffnameOverrides[molType] ?: molType.defaultForcefieldNameOrThrow

		// get charge generation settings if small molecule
		val chargeMethod = when (molType) {
			MoleculeType.SmallMolecule -> chargeMethod
			else -> null
		}

		// get the amber types for the molecule
		val types = try {

			mol.calcTypesAmber(mol.findTypeOrThrow(), ffname, chargeMethod, netCharge)

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

		// calculate the frcmod, if needed
		val frcmod = mol.calcModsAmber(types)

		return MolParams(mol, types, frcmod)
	}


	/** Amber forcefields don't have internal energies */
	override fun internalEnergy(molParams: ForcefieldParams.MolParams, atom: Atom) = null


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

	data class TopKey(val molaParams: MolParams, val molbParams: MolParams)
	private val topCache = HashMap<TopKey,AmberTopology.Mapped>()

	override fun pairParams(molaParams: ForcefieldParams.MolParams, atoma: Atom, molbParams: ForcefieldParams.MolParams, atomb: Atom, dist: Int?): PairParams? {

		// Amber forcefields only have "non-bonded" interactions between atoms at least 1-4 bonded (ie >= 3 bonds away)
		// so skip the params if we're less than 3 bonds away
		if (dist != null && dist < 3) {
			return null
		}

		molaParams as MolParams
		molbParams as MolParams

		// get the amber "topology" which has the real forcefield parameters in it
		val top = topCache.getOrPut(TopKey(molaParams, molbParams)) {

			// cache miss, calcuate the topology for this molecule pair

			// collect the molecules and amber types
			// collapse identical molecules if possible
			val molsAndTypes = if (molaParams === molbParams) {
				listOf(molaParams.mol to molaParams.types)
			} else {
				listOf(
					molaParams.mol to molaParams.types,
					molbParams.mol to molbParams.types
				)
			}

			// run amber to get the params
			val frcmods = listOf(molaParams, molbParams).mapNotNull { it.frcmod }
			val params = molsAndTypes.calcParamsAmber(frcmods)

			// NOTE: calcParamsAmber() eventually calls LEaP in a separate process
			// profiling shows this is by far the bottleneck in compiling atom pairs
			// maybe there's something we can do to speed this up so compiling goes faster?

			// read the amber forcefield params from the topology file
			val mols = molsAndTypes.map { (mol, _) -> mol }
			TopIO.read(params.top).mapTo(mols)
		}

		// get the electrostatic params
		// NOTE: the coulomb factor (~322.05) is already pre-multiplied into the charges
		// see: Amber manual 14.1.7, Partial Charges
		var esQ = (top.charge(atoma) * top.charge(atomb))/dielectric

		// get the van der Waals params
		// NOTE: Lorentz/Berthelot mixing rules already pre-applied to A and B
		// see: Amber manual 14.1.7, Van der Waals Parameters
		var (vdwA, vdwB) = top.vdw(atoma, atomb)

		/* Now, to apply Osprey's special brand of van der Waals scaling:

			We actually want to scale the vdW radii, rather than the vdW potential value,
			so let's breakdown the potential in terms of radii and other parameters:

			The following equation references are from the Amber manual v19.

			The 6-12 van der Waals potential has the general form given by Eqn 14.10:

			Vij = Aij/rij^12 - Bij/rij^6

			where rij is the radius (ie distance) between atoms i and j,
			and Aij and Bij are defined in Eqn 14.12:

			Aij = eij*Rmin^12
			Bij = 2*eij*Rmin^6

			where eij is defined in Eqn 14.14:

			eij = sqrt(eii*ejj)
			assuming eii = ei and ejj = ej, we can rewrite:
			eij = sqrt(ei*ej)

			and Rmin is defined in the text after Eqn 14.8:

			Rmin = Ri + Rj

			Here, ei and Ri are parameters defined for the Amber forcefields for
			atom i and we can assume they're given and constant.

			NOTE: Eqn 14.13 suggests a different mixing equation for Rmin:
			Rminij = 0.5*(Rmini + Rminj)
			assuming, Rminij = Rmin, Rmini = Ri, and Rminj = Rj, we can re-write:
			Rmin = 0.5*(Ri + Rj)

			After analying the outputs of LEaP, it appears that LEaP does NOT use Eqn 14.13 at all,
			not even when the two atom types are different.
			So the 0.5 scaling factor does not appear to be applied during LEaP's calculations.
			Dropping the 0.5 scaling factor allows us to produce values for Aij and Bij
			that are identical to LEaP's values.

			Ok, back to the derivation...

			To apply Osprey's version of vdW scaling, we need to multiply
			Ri and Rj by a factor of s:
			Let's massage Eqn 14.12 a bit to expose Ri and Rj:

			Aij = eij*Rmin^12
			    = eij*( Ri + Rj )^12

			Now we apply the scaling factor s to Ri and Rj:

			   eij*( Ri*s + Rj*s )^12
			 = eij*[ s*(Ri + Rj) ]^12
			 = eij*(Ri + Rj)^12*s^12
			 = eij*Rmin^12*s^12
			 = Aij*s^12

			Therefore, Aij gets scaled by s^12,
			and similarly, Bij gets scaled by s^6.
		*/

		// apply the parametric vdW scaling
		val s2 = vdwScale*vdwScale
		val s6 = s2*s2*s2
		val s12 = s6*s6
		vdwA *= s12
		vdwB *= s6

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

	override fun calcEnergy(atomsByMols: Map<Molecule,List<Atom>>, molParamsByMols: Map<Molecule,ForcefieldParams.MolParams>, molToParams: Map<Molecule,AtomMap>?): Double {

		var energy = 0.0

		// add the pair energies
		ForcefieldParams.forEachPair(atomsByMols, atomsByMols) { mola, atoma, molb, atomb, dist ->

			// map the atoms to the params molecules
			val molaParams = molParamsByMols.getValue(mola)
			val patoma = molToParams?.getValue(mola)?.getBOrThrow(atoma) ?: atoma
			val molbParams = molParamsByMols.getValue(molb)
			val patomb = molToParams?.getValue(molb)?.getBOrThrow(atomb) ?: atomb

			pairParams(molaParams, patoma, molbParams, patomb, dist)?.let { params ->
				energy += params.calcEnergy(atoma.pos.distance(atomb.pos))
			}
		}

		return energy
	}
}
