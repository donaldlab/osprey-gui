package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.*


/**
 * Generates chain ids in the range A-Z.
 */
open class ChainIdGeneratorAZ : ChainIdGenerator {

	private val usedIds = HashSet<String>()
	private var nextChainId = 'A'

	override fun setUsedIds(ids: Collection<String>) {
		usedIds.clear()
		usedIds.addAll(ids)
	}

	private fun getNextId(): String {
		if (nextChainId > 'Z') {
			throw IllegalStateException("out of unique chain ids in A-Z")
		}
		return "${nextChainId++}"
	}

	override fun generateId(): String {
		var chainId = getNextId()
		while (chainId in usedIds) {
			chainId = getNextId()
		}
		usedIds.add(chainId)
		return chainId
	}
}


/**
 * Generates single-residue chains for non-polymer molecules.
 */
class ChainGeneratorSingleResidue(val idGenerator: ChainIdGenerator) : ChainGenerator {

	override fun setUsedIds(ids: Collection<String>) =
		idGenerator.setUsedIds(ids)

	override fun generateChain(nonPolymerMol: Molecule, polymerAtoms: List<Atom>) =
		Polymer.Chain(idGenerator.generateId()).apply {
			residues.add(Polymer.Residue(
				"1",
				nonPolymerMol.type ?: "SML",
				polymerAtoms
			))
		}
}
