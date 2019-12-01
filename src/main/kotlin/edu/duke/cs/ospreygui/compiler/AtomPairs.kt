package edu.duke.cs.ospreygui.compiler

import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import edu.duke.cs.ospreygui.tools.pairs
import java.util.HashMap


class AtomPairs(index: ConfSpaceIndex, forcefields: List<ForcefieldParams>) {

	data class AtomPair(
		val atomi1: Int,
		val atomi2: Int,
		val paramsi: Int
	)

	inner class PosSingles(index: ConfSpaceIndex) {

		// pre-allocate enough storage for every position and conf
		val list: List<MutableList<List<AtomPair>?>> =
			index.positions.map { posInfo ->
				posInfo.confs
					.map {
						// IDEA is lying about the useless cast warning ...
						// the compiler apparently needs a little help figuring out this type
						@Suppress("USELESS_CAST")
						null as List<AtomPair>?
					}
					.toMutableList()
			}

		operator fun set(posi1: Int, confi1: Int, atomPairs: List<AtomPair>) {
			list[posi1][confi1] = atomPairs
		}

		operator fun get(posi1: Int, confi1: Int): List<AtomPair> =
			list[posi1][confi1]
				?: throw NoSuchElementException("position:conformation $posi1:$confi1 has no atom pairs")
	}
	val singles = PosSingles(index)
	val statics = PosSingles(index)

	inner class PosPairs(index: ConfSpaceIndex) {

		// pre-allocate enough storage for every position pair and conf pair
		private val list: List<List<MutableList<List<AtomPair>?>>> =
			index.positions.pairs()
				.map { (posInfo1, posInfo2) ->
					posInfo1.confs.map {
						posInfo2.confs.map {
							// IDEA is lying about the useless cast warning ...
							// the compiler needs a little help figuring out this type
							@Suppress("USELESS_CAST")
							null as List<AtomPair>?
						}
						.toMutableList()
					}
				}

		private fun posIndex(posi1: Int, posi2: Int): Int {
			if (posi2 < posi1) {
				return posi1*(posi1 - 1)/2 + posi2
			} else {
				throw IllegalArgumentException("posi2 $posi2 must be strictly less than posi1 $posi1")
			}
		}

		operator fun set(posi1: Int, confi1: Int, posi2: Int, confi2: Int, atomPairs: List<AtomPair>) {
			list[posIndex(posi1, posi2)][confi1][confi2] = atomPairs
		}

		operator fun get(posi1: Int, confi1: Int, posi2: Int, confi2: Int): List<AtomPair> =
			list[posIndex(posi1, posi2)][confi1][confi2]
				?: throw NoSuchElementException("position:conformation pair $posi1:$confi2 - $posi2:$confi2 has no atom pairs")
	}
	val pairs = PosPairs(index)

	/**
	 * Different atom pairs will often yield the exact same forcefield parameters.
	 * This cache allows us to de-duplicate the params and save quite a bit of space/time.
	 */
	class ParamsCache(
		forcefields: List<ForcefieldParams>,
		private val list: ArrayList<List<Double>> = ArrayList()
	) : List<List<Double>> by list {

		private val indices = HashMap<List<Double>,Int>()

		fun index(params: List<Double>): Int {

			// check the cache first
			indices[params]?.let { return it }

			// cache miss, add the params
			val index = list.size
			list.add(params)
			indices[params] = index
			return index
		}
	}
	val paramsCache = ParamsCache(forcefields)
}
