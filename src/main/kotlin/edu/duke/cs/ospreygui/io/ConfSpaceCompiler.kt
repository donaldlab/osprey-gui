package edu.duke.cs.ospreygui.io

import cuchaz.kludge.tools.x
import cuchaz.kludge.tools.y
import cuchaz.kludge.tools.z
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.tools.identityHashMapOf
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import edu.duke.cs.ospreygui.prep.ConfSpace
import org.joml.Vector3dc
import java.util.*
import kotlin.collections.ArrayList


/**
 * Collects all the information from a conformation space,
 * combines it with forcefield parameters,
 * and emits a TOML file that describes the parameterized
 * conformation space to Osprey in a totally unambiguous way.
 */
class ConfSpaceCompiler(val confSpace: ConfSpace) {

	private val params: MutableList<ForcefieldParams> = ArrayList()

	fun addForcefield(ff: Forcefield) {
		if (ff !in forcefields) {
			params.add(ff.parameterizer())
		}
	}

	val forcefields: List<Forcefield> get() =
		params
			.map { it.forcefield }


	data class Message(
		val type: Type,
		val message: String,
		val context: Any? = null
	) {

		enum class Type {
			Warning,
			Error
		}

		private val contextMsg: String =
			context
				?.toString()
				?.let { "; ctx=$it" }
				?: ""

		override fun toString() =
			"$type: $message$contextMsg"
	}

	data class Report(
		val messages: List<Message>,
		val toml: String?
	) {

		val warnings get() = messages.filter { it.type == Message.Type.Warning }
		val errors get() = messages.filter { it.type == Message.Type.Error }
	}

	/**
	 * Compiles the conf space and the forcefields.
	 * Vigorously throws errors if something goes wrong.
	 */
	fun compile(): Report {

		val buf = StringBuilder()
		fun write(str: String, vararg args: Any) = buf.append(String.format(str, *args))

		val messages = ArrayList<Message>()
		fun warn(msg: String, context: Any? = null) =
			messages.add(Message(Message.Type.Warning, msg, context))
		fun error(msg: String, context: Any? = null) =
			messages.add(Message(Message.Type.Error, msg, context))

		try {

			// TODO: check for missing forcefield params and issue warnings/errors

			// TODO: NEXTTIME: confs can change the atom types of the fixed atoms!!
			//   how to fix? Move the changed atoms into the confs??
			//   !!!!!!!!!!!!!!!

			write("\n")
			write("name = %s\n", confSpace.name.quote())
			write("forcefields = [ %s ]\n",
				forcefields.joinToString(", ") { it.name.toLowerCase().quote() }
			)

			// write out the fixed atoms in order
			write("\n")
			write("[fixed]\n")
			write("atoms = [\n")
			val fixedAtomsByMol = confSpace.fixedAtoms()
				.mapValues { (_, atoms) -> atoms.toList() }
			val fixedAtomIndices = IdentityHashMap<Atom,Int>()
			val fixedAtomNames = IdentityHashMap<Atom,String>()
			for ((mol, atoms) in confSpace.fixedAtoms()) {
				for (atom in atoms) {

					// assign the atom index
					val index = fixedAtomIndices.size
					fixedAtomIndices[atom] = index

					// make a globally-unique atom name
					val resName = (mol as? Polymer)
						?.let {
							it.findChainAndResidue(atom)?.let { (chain, res) ->
								"-${chain.id}${res.id}"
							}
						}
						?: ""
					val atomName = "${mol.name}$resName-${atom.name}"
					fixedAtomNames[atom] = atomName

					write("\t{ xyz = %s, name = %20s }, # %-6d\n",
						atom.pos.toToml(),
						fixedAtomNames.getValue(atom),
						fixedAtomIndices.getValue(atom)
					)
				}
			}
			write("]\n")

			// write out the internal energies
			write("[fixed.energy]\n")
			for ((ffi, ff) in params.withIndex()) {
				write("%d = %f\n",
					ffi,
					ff.calcEnergy(fixedAtomsByMol)
				)
			}

			// cache all the forcefield parameters,
			// so we can de-duplicate them and condense the compiled output
			// (there often far far fewer forcefield atom type pairs then atom pairs,
			// so the savings here is huge! YUUUGE!!)
			class ParamsCache {

				val paramsList = ArrayList<List<Double>>()
				val indices = HashMap<List<Double>,Int>()

				fun index(params: List<Double>): Int {

					// check the cache first
					indices[params]?.let { return it }

					// cache miss, add the params
					val index = paramsList.size
					paramsList.add(params)
					indices[params] = index
					return index
				}
			}
			val paramsCaches = ArrayList<ParamsCache>().apply {
				for (ff in params) {
					add(ParamsCache())
				}
			}

			// write the design positions and conformations
			val positions = confSpace.positions()
			for ((posi, pos) in positions.withIndex()) {

				write("\n")
				write("[pos.$posi] # %s\n",
					pos.name
				)
				write("name = %s\n", pos.name.quote())

				// write the confs
				var confi = 0
				confSpace.forEachConf(pos) { frag, conf ->

					write("\n")
					write("[pos.$posi.conf.$confi] # %s:%s:%s\n",
						pos.name, frag.id, conf.id
					)
					write("id = %s\n", conf.id.quote())

					// write the atoms, and track the indices
					val posAtomIndices = IdentityHashMap<Atom,Int>()
					write("atoms = [\n")
					for ((atomi, atomInfo) in frag.atoms.withIndex()) {
						val atom = pos.atomResolverOrThrow.resolveOrThrow(atomInfo)
						posAtomIndices[atom] = atomi
						write("\t{ xyz = %s, name = %8s }, # %-2d\n",
							atom.pos.toToml(),
							atomInfo.name.quote(),
							atomi
						)
					}
					write("]\n")

					// write the internal params
					write("[pos.$posi.conf.$confi.params.internal] # %s:%s:%s\n",
						pos.name, frag.id, conf.id
					)
					for ((ffi, ff) in params.withIndex()) {
						write("%d = [\n", ffi)
						for (atom in pos.currentAtoms) {
							val p = ff.singleParams(pos.mol, atom) ?: continue
							write("\t%d, # %s\n",
								paramsCaches[ffi].index(p),
								atom.name
							)
						}
						write("]\n")
					}

					val posAtomsByMol = identityHashMapOf(pos.mol to pos.currentAtoms.toList())

					// write the pos-fixed params
					write("[pos.$posi.conf.$confi.params.fixed] # %s:%s:%s\n",
						pos.name, frag.id, conf.id
					)
					for ((ffi, ff) in params.withIndex()) {
						write("%d = [\n", ffi)
						ForcefieldParams.forEachPair(posAtomsByMol, fixedAtomsByMol) { molp, atomp, molf, atomf, dist ->
							ff.pairParams(molp, atomp, molf, atomf, dist)?.let { p ->
								write("\t[ %2d, %6d, %6d ], # %s, %s\n",
									posAtomIndices.getValue(atomp),
									fixedAtomIndices.getValue(atomf),
									paramsCaches[ffi].index(p),
									atomp.name,
									fixedAtomNames.getValue(atomf)
								)
							}
						}
						write("]\n")
					}

					// write the pos-fixed params
					for (posbi in 0 until posi) {
						val posb = positions[posbi]

						var confbi = 0
						confSpace.forEachConf(posb) { fragb, confb ->

							val posbAtomsByMol = identityHashMapOf(posb.mol to posb.currentAtoms.toList())

							write("\n")
							write("[pos.$posi.conf.$confi.params.pos.$posbi.conf.$confbi] # %s:%s:%s - %s:%s:%s\n",
								pos.name, frag.id, conf.id,
								posb.name, fragb.id, confb.id
							)
							for ((ffi, ff) in params.withIndex()) {
								write("%d = [\n", ffi)
								ForcefieldParams.forEachPair(posAtomsByMol, posbAtomsByMol) { mola, atoma, molb, atomb, dist ->
									ff.pairParams(mola, atoma, molb, atomb, dist)?.let { p ->

										val atombi = fragb.atoms
											.indexOfFirst { it.name == atomb.name }
											.takeIf { it >= 0 }
											?: throw RuntimeException("can't find atom $atomb in fragment $fragb")

										write("\t[ %2d, %2d, %6d ], # %s, %s\n",
											posAtomIndices.getValue(atoma),
											atombi,
											paramsCaches[ffi].index(p),
											atoma.name,
											atomb.name
										)
									}
								}
								write("]\n")
							}

							confbi += 1
						} // forEachConf b
					}

					confi += 1
				} // forEachConf
			}

			// write all the collected forcefield params
			write("\n")
			write("[ffparams]\n")
			for (ffi in params.indices) {
				write("%d = [\n", ffi)
				val paramsCache = paramsCaches[ffi]
				for ((i, params) in paramsCache.paramsList.withIndex()) {
					write("\t[ %s ], # %d\n",
						params.joinToString(", ") { it.toString() },
						i
					)
				}
				write("]\n")
			}

		} finally {

			// finally, return the compiler result
			val toml = if (messages.any { it.type == Message.Type.Error }) {
				// don't send out a partial/broken toml if there are errors
				null
			} else {
				buf.toString()
			}
			return Report(messages, toml)
		}
	}
}

private fun String.quote() = "'$this'"

private fun Vector3dc.toToml() =
	"[ %12.6f, %12.6f, %12.6f ]".format(x, y, z)
