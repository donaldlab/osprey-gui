package edu.duke.cs.ospreygui.forcefield.amber

import edu.duke.cs.molscope.molecule.*
import edu.duke.cs.molscope.tools.associateIdentity
import edu.duke.cs.ospreygui.io.*
import edu.duke.cs.ospreyservice.services.ForcefieldParamsRequest
import edu.duke.cs.ospreyservice.services.MoleculeFFInfoRequest
import edu.duke.cs.ospreyservice.services.TypesRequest
import java.util.*


enum class AmberAtomTypes(val id: String) {
	Gaff("gaff"),
	Gaff2("gaff2"),
	Amber("amber"),
	BCC("bcc"),
	SYBYL("sybyl")
}

data class AmberTypes(
	val ffname: ForcefieldName,
	val atomTypes: Map<Atom,String>,
	val bondTypes: Map<AtomPair,String>,
	val atomCharges: Map<Atom,String>
) {

	constructor(ffname: ForcefieldName, mol2Metadata: Mol2Metadata)
		: this(ffname, mol2Metadata.atomTypes, mol2Metadata.bondTypes, mol2Metadata.atomCharges)

	fun toMol2Metadata(mol: Molecule): Mol2Metadata {

		val metadata = Mol2Metadata()

		// copy over the atom and bond types
		metadata.atomTypes.putAll(atomTypes)
		metadata.bondTypes.putAll(bondTypes)
		metadata.atomCharges.putAll(atomCharges)

		if (mol is Polymer) {
			for (chain in mol.chains) {
				for (res in chain.residues) {
					metadata.dictionaryTypes[res] = Mol2Metadata.defaultDictionaryType
				}
			}
		}

		return metadata
	}

	/**
	 * Returns a copy of this class, but keys all the maps
	 * with equivalent atoms in the destination molecule.
	 */
	fun transferTo(dst: Molecule): AmberTypes {

		val dstAtoms = dst.atoms.associateWith { it }
		fun Atom.match() = dstAtoms[this]
			?: throw NoSuchElementException("destination molecule doesn't have equivalent atom for $this")

		return AmberTypes(
			ffname = ffname,
			atomTypes = atomTypes
				.mapKeys { (srcAtom, _) ->
					srcAtom.match()
				},
			bondTypes = bondTypes
				.mapKeys { (srcBond, _) ->
					AtomPair(srcBond.a.match(), srcBond.b.match())
				},
			atomCharges = atomCharges
				.mapKeys { (srcAtom, _) ->
					srcAtom.match()
				}
		)
	}
}

enum class AmberChargeMethod(val id: String) {
	RESP("resp"),
	AM1BCC("bcc"),
	CM1("cm1"),
	CM2("cm2"),
	ESP("esp"),
	Mulliken("mul"),
	Gasteiger("gas")
}


data class AmberChargeGeneration(
	val method: AmberChargeMethod,
	val netCharge: Int,
	val minimizationSteps: Int
)

/**
 * Calculate Amber types for atoms and bonds.
 * Pass values to `chargeMethod` and `netCharge` to calculate partial charges for small molecules as well.
 */
fun Molecule.calcTypesAmber(
	molType: MoleculeType,
	ffname: ForcefieldName = molType.defaultForcefieldNameOrThrow,
	generateCharges: AmberChargeGeneration? = null
): AmberTypes {

	val dst = this

	val (srcMetadata, atomMap) = when (molType) {

		MoleculeType.SmallMolecule -> {

			// call osprey service with small molecule settings
			val request = TypesRequest.SmallMoleculeSettings(
				mol2 = dst.toMol2(),
				atomTypes = ffname.atomTypesOrThrow.id,
				chargeSettings = generateCharges?.let {
					TypesRequest.ChargeSettings(
						chargeMethod = it.method.id,
						netCharge = it.netCharge,
						numMinimizationSteps = it.minimizationSteps
					)
				}
			).toRequest()
			val (src, srcMetadata) = Molecule.fromMol2WithMetadata(OspreyService.types(request).mol2)

			// Tragically, we antechamber doesn't write the residue info back into the mol2 file,
			// so we can't use our usual MoleculeMapper to do the atom mapping here.
			// Thankfully, the atom order is preserved, so we can use that to do the mapping instead.
			// val mapper = src.mapTo(dst)
			srcMetadata to src.atoms.associateIdentity { srcAtom ->
				srcAtom to dst.atoms[src.atoms.indexOf(srcAtom)]
			}
		}

		// for everything else, call osprey service with molecule settings
		else -> {

			val request = TypesRequest.MoleculeSettings(
				pdb = dst.toPDB(
					// amber will only see the disulfide bonds if we send
					// CYX residues, SSBOND records, and CONECT records
					translateSSasCYX = true,
					includeSSBondConect = true,
					// amber also needs histidine protonation state explicitly described
					translateHIStoEDP = true,
					// these errors will cause downstream problems, so fail loudly and early
					throwOnNonChainPolymerAtoms = true
				),
				ffname = ffname.name
			).toRequest()
			val (src, srcMetadata) = Molecule.fromMol2WithMetadata(OspreyService.types(request).mol2)

			// check for unmapped atoms with the dst->src mapping
			MoleculeMapper(dst, src)
				.let { dstToSrc ->
					dst.atoms.filter { dstToSrc.mapAtom(it) == null }
				}
				.takeIf { it.isNotEmpty() }
				?.map { atom ->
					// get descriptive info for the atom
					(dst as? Polymer)
						?.findChainAndResidue(atom)
						?.let { (chain, res) ->
							"${atom.name} @ ${chain.id}${res.id}"
						}
						?: atom.name
				}
				?.let { unmappedAtoms ->
					throw NoSuchElementException("LEaP didn't generate atom info for ${unmappedAtoms.size} atom(s):\n$unmappedAtoms")
				}

			// use the molecule mapper to map the metadata back with the src->dst mapping
			val srcToDst = MoleculeMapper(src, dst)
			srcMetadata to src.atoms.associateIdentity { srcAtom ->
				srcAtom to srcToDst.mapAtom(srcAtom)
			}
		}
	}

	// translate the metadata back
	val dstMetadata = Mol2Metadata()
	for ((srcAtom, type) in srcMetadata.atomTypes) {
		val dstAtom = atomMap.getValue(srcAtom) ?: continue
		dstMetadata.atomTypes[dstAtom] = type
	}
	for ((srcBond, type) in srcMetadata.bondTypes) {
		val dsta = atomMap.getValue(srcBond.a) ?: continue
		val dstb = atomMap.getValue(srcBond.b) ?: continue
		dstMetadata.bondTypes[AtomPair(dsta, dstb)] = type
	}
	for ((srcAtom, charge) in srcMetadata.atomCharges) {
		val dstAtom = atomMap.getValue(srcAtom) ?: continue
		dstMetadata.atomCharges[dstAtom] = charge
	}

	return AmberTypes(ffname, dstMetadata)
}


fun Molecule.calcModsAmber(types: AmberTypes): String? {
	val mol2 = toMol2(types.toMol2Metadata(this))
	return OspreyService.moleculeFFInfo(MoleculeFFInfoRequest(mol2, types.ffname.name)).ffinfo
}

data class AmberMolParams(
	val mol: Molecule,
	val types: AmberTypes,
	val frcmod: String?
)

data class AmberParams(
	val top: String,
	val crd: String
)

fun List<AmberMolParams>.calcParamsAmber(): AmberParams {

	val response = OspreyService.forcefieldParams(ForcefieldParamsRequest(
		map {
			ForcefieldParamsRequest.MolInfo(
				mol2 = it.mol.toMol2(it.types.toMol2Metadata(it.mol)),
				ffname = it.types.ffname.name,
				ffinfo = it.frcmod
			)
		}
	))

	return AmberParams(response.params, response.coords)
}

fun AmberMolParams.calcParamsAmber() =
	listOf(this).calcParamsAmber()
