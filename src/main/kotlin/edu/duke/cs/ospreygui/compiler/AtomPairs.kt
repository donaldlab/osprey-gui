package edu.duke.cs.ospreygui.compiler

import edu.duke.cs.ospreygui.tools.pairs
import java.util.HashMap


class AtomPairs(index: ConfSpaceIndex) {

	data class AtomPair(
		val atomi1: Int,
		val atomi2: Int,
		val paramsi: Int
	)

	inner class PosSingles(index: ConfSpaceIndex) {

		// pre-allocate enough storage for every position and conf
		private val list: List<List<MutableList<AtomPair>?>> =
			index.positions.map { posInfo ->
				posInfo.confs
					.map {
						// IDEA is apparently lying about the warning here ...
						// looks like the compiler needs a little help figuring out this type
						@Suppress("RemoveExplicitTypeArguments")
						ArrayList<AtomPair>()
					}
			}

		fun add(posi1: Int, confi1: Int, atomi1: Int, atomi2: Int, params: List<Double>) {
			list[posi1][confi1]?.add(AtomPair(atomi1, atomi2, paramsCache.index(params)))
				?: throw NoSuchElementException("position:conformation $posi1:$confi1 is not in this conf space")
		}

		operator fun get(posi1: Int, confi1: Int): List<AtomPair> =
			list[posi1][confi1]
				?: throw NoSuchElementException("position:conformation $posi1:$confi1 is not in this conf space")
	}
	val singles = PosSingles(index)
	val statics = PosSingles(index)

	inner class PosPairs(index: ConfSpaceIndex) {

		// pre-allocate enough storage for every position pair and conf pair
		private val list: List<List<List<MutableList<AtomPair>?>>> =
			index.positions.pairs()
				.map { (posInfo1, posInfo2) ->
					posInfo1.confs.map {
						posInfo2.confs.map {
							// IDEA is apparently lying about the warning here ...
							// looks like the compiler needs a little help figuring out this type
							@Suppress("RemoveExplicitTypeArguments")
							ArrayList<AtomPair>()
						}
					}
				}

		private fun posIndex(posi1: Int, posi2: Int): Int {
			if (posi2 < posi1) {
				return posi1*(posi1 - 1)/2 + posi2
			} else {
				throw IllegalArgumentException("posi2 $posi2 must be strictly less than posi1 $posi1")
			}
		}

		fun add(posi1: Int, confi1: Int, posi2: Int, confi2: Int, atomi1: Int, atomi2: Int, params: List<Double>) {
			list[posIndex(posi1, posi2)][confi1][confi2]?.add(AtomPair(atomi1, atomi2, paramsCache.index(params)))
				?: throw NoSuchElementException("position:conformation pair $posi1:$confi2 - $posi2:$confi2 is not in this conf space")
		}

		operator fun get(posi1: Int, confi1: Int, posi2: Int, confi2: Int): List<AtomPair> =
			list[posIndex(posi1, posi2)][confi1][confi2]
				?: throw NoSuchElementException("position:conformation pair $posi1:$confi2 - $posi2:$confi2 is not in this conf space")
	}
	val pairs = PosPairs(index)

	/**
	 * Different atom pairs will often yield the exact same forcefield parameters.
	 * This cache allows us to de-duplicate the params and save quite a bit of space/time.
	 */
	class ParamsCache(
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
	val paramsCache = ParamsCache()
}
