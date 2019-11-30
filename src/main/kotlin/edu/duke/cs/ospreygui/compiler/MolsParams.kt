package edu.duke.cs.ospreygui.compiler

import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.tools.associateIdentity
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.prep.DesignPosition
import java.util.*


/**
 * A convenient place to store molecule forcefield parameterizations
 * for an indexed conformation space
 */
class MolsParams(val index: ConfSpaceIndex) {

	private class ForPos {

		var wildType: ForcefieldParams.MolParams? = null

		val byConf = IdentityHashMap<DesignPosition,IdentityHashMap<ConfLib.Conf,ForcefieldParams.MolParams>>()

		operator fun get(pos: DesignPosition, conf: ConfLib.Conf) =
			byConf.get(pos)?.get(conf)

		operator fun set(pos: DesignPosition, conf: ConfLib.Conf, molParams: ForcefieldParams.MolParams) {
			byConf.getOrPut(pos) { IdentityHashMap() }[conf] = molParams
		}
	}

	private inner class ForForcefield {

		val wildTypes = IdentityHashMap<Molecule,ForcefieldParams.MolParams>()

		// pre-allocate enough storage for every position and conf
		val confs: List<MutableList<ForcefieldParams.MolParams?>> =
			index.positions.map { posInfo ->
				posInfo.confs
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

	operator fun set(ff: ForcefieldParams, confInfo: ConfSpaceIndex.ConfInfo, params: ForcefieldParams.MolParams) {
		this[ff].confs[confInfo.posInfo.index][confInfo.index] = params
	}

	operator fun get(ff: ForcefieldParams, confInfo: ConfSpaceIndex.ConfInfo): ForcefieldParams.MolParams =
		this[ff].confs[confInfo.posInfo.index][confInfo.index]
			?: throw NoSuchElementException("no params for conformation ${confInfo.posInfo.pos.name} = ${confInfo.id} in forcefield $ff")

	fun getWildTypeByMol(ff: ForcefieldParams): Map<Molecule,ForcefieldParams.MolParams> =
		this[ff].wildTypes.keys.associateIdentity { mol -> mol to this[ff, mol] }
}
