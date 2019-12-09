package edu.duke.cs.ospreygui.compiler

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.tools.associateIdentity
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import java.util.*


/**
 * A convenient place to store molecule forcefield parameterizations
 * for an indexed conformation space
 */
class MolsParams(val index: ConfSpaceIndex) {

	private inner class ForForcefield {

		val wildTypes = IdentityHashMap<Molecule,ForcefieldParams.MolParams>()

		// pre-allocate enough storage for every position and fragment
		val frags: List<MutableList<ForcefieldParams.MolParams?>> =
			index.positions.map { posInfo ->
				posInfo.fragments
					.map {
						// IDEA is lying about the useless cast warning ...
						// the compiler apparently needs a little help figuring out this type
						@Suppress("USELESS_CAST")
						null as ForcefieldParams.MolParams?
					}
					.toMutableList()
			}
	}

	private val paramsByFF = IdentityHashMap<ForcefieldParams,ForForcefield>()

	private operator fun get(ff: ForcefieldParams) =
		paramsByFF.getOrPut(ff) { ForForcefield() }

	operator fun set(ff: ForcefieldParams, mol: Molecule, params: ForcefieldParams.MolParams) {
		this[ff].wildTypes[mol] = params
	}

	operator fun get(ff: ForcefieldParams, mol: Molecule): ForcefieldParams.MolParams =
		this[ff].wildTypes[mol]
			?: throw NoSuchElementException("no wild-type params for molecule $mol in forcefield $ff")

	operator fun set(ff: ForcefieldParams, fragInfo: ConfSpaceIndex.FragInfo, params: ForcefieldParams.MolParams) {
		this[ff].frags[fragInfo.posInfo.index][fragInfo.index] = params
	}

	operator fun get(ff: ForcefieldParams, fragInfo: ConfSpaceIndex.FragInfo): ForcefieldParams.MolParams =
		this[ff].frags[fragInfo.posInfo.index][fragInfo.index]
			?: throw NoSuchElementException("no params for fragment ${fragInfo.posInfo.pos.name} = ${fragInfo.frag.id} in forcefield $ff")

	fun getWildTypeByMol(ff: ForcefieldParams): Map<Molecule,ForcefieldParams.MolParams> =
		this[ff].wildTypes.keys.associateIdentity { mol -> mol to this[ff, mol] }
}
