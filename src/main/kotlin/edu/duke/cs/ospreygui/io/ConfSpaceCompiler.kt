package edu.duke.cs.ospreygui.io

import cuchaz.kludge.tools.x
import cuchaz.kludge.tools.y
import cuchaz.kludge.tools.z
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.tools.associateIdentity
import edu.duke.cs.molscope.tools.identityHashMapOf
import edu.duke.cs.molscope.tools.identityHashSet
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.forcefield.eef1.EEF1ConfSpaceParams
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

	private val ffparams: MutableList<ForcefieldParams> = ArrayList()

	fun addForcefield(ff: Forcefield) {
		if (ff !in forcefields) {
			ffparams.add(ff.parameterizer())
		}
	}

	val forcefields: List<Forcefield> get() =
		ffparams
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

			write("\n")
			write("name = %s\n", confSpace.name.quote())
			write("forcefields = [ %s ]\n",
				forcefields.joinToString(", ") { it.name.toLowerCase().quote() }
			)

			// TODO: issues warnings/errors for:
			//   missing forcefield params
			//   invalid (eg NaN) coords for atoms

			// get the authoritative list of conf space positions in order
			val positions = confSpace.positions()

			// find out what fixed atoms have ff params that are affected by changes in conformations
			// and add them to the atoms list for all the conformations at that design position
			// and also subtract them from the list of fixed atoms

			// first, analyze the current atoms
			val fixedAtoms = confSpace.fixedAtoms()
			val analyses = ffparams.associateWith { it.analyze(fixedAtoms) }

			// then look through all the confs to find the changed atoms
			val dynamicFixedAtoms = positions
				.associateWith { pos ->
					val changedAtoms = identityHashSet<Atom>()
					confSpace.forEachConf(pos) { frag, conf ->
						for ((ff, analysis) in analyses) {
							changedAtoms.addAll(analysis.findChangedAtoms(ff.analyze(fixedAtoms)))
						}
					}
					return@associateWith changedAtoms
				}
			fun Atom.isDynamic() =
				dynamicFixedAtoms.values.any { this in it }

			// static atoms are the subset of fixed atoms that don't change forcefield params
			// collect all the static atoms
			val staticAtomsByMol = fixedAtoms
				.mapValues { (_, atoms) ->
					atoms.filter { !it.isDynamic() }
				}

			// TODO: if positions share any dynamic fixed atoms, throw an error

			// write out the static atoms in order
			write("\n")
			write("[static]\n")
			write("atoms = [\n")
			val staticAtomIndices = IdentityHashMap<Atom,Int>()
			val staticAtomNames = IdentityHashMap<Atom,String>()
			for ((mol, atoms) in staticAtomsByMol) {
				for (atom in atoms) {

					// assign the atom index
					val index = staticAtomIndices.size
					staticAtomIndices[atom] = index

					// make a globally-unique atom name, since static atoms can come from different molecules
					val resName = (mol as? Polymer)
						?.let {
							it.findChainAndResidue(atom)?.let { (chain, res) ->
								"-${chain.id}${res.id}"
							}
						}
						?: ""
					val atomName = "${mol.name}$resName-${atom.name}"
					staticAtomNames[atom] = atomName

					write("\t{ xyz = %s, name = %20s }, # %d\n",
						atom.pos.toToml(),
						staticAtomNames.getValue(atom),
						staticAtomIndices.getValue(atom)
					)
				}
			}
			write("]\n")

			// write out the internal energies for the static atoms
			write("[static.energy]\n")
			for ((ffi, ff) in ffparams.withIndex()) {
				write("%d = %f\n",
					ffi,
					ff.calcEnergy(staticAtomsByMol)
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
				for (ff in ffparams) {
					add(ParamsCache())
				}
			}

			// write the design positions and conformations
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
					write("type = %s\n", frag.type.quote())

					// write the atoms, and track the indices
					val confAtoms = dynamicFixedAtoms.getValue(pos) +
						frag.atoms.map { pos.atomResolverOrThrow.resolveOrThrow(it) }
					val confAtomIndices = confAtoms
						.withIndex()
						.associateIdentity { (i, atom) -> atom to i }
					write("atoms = [\n")
					for ((atomi, atom) in confAtoms.withIndex()) {
						write("\t{ xyz = %s, name = %8s }, # %d, dynamic\n",
							atom.pos.toToml(),
							atom.name.quote(),
							atomi
						)
					}
					write("]\n")

					// write the internal params
					write("[pos.$posi.conf.$confi.params.internal] # %s:%s:%s\n",
						pos.name, frag.id, conf.id
					)
					for ((ffi, ff) in ffparams.withIndex()) {
						write("%d = [\n", ffi)
						for (atom in confAtoms) {
							val p = ff.singleParams(pos.mol, atom) ?: continue
							write("\t%d, # %s\n",
								paramsCaches[ffi].index(p),
								atom.name
							)
						}
						write("]\n")
					}

					val confAtomsByMol = identityHashMapOf(pos.mol to confAtoms.toList())

					// write the pos-static params
					write("[pos.$posi.conf.$confi.params.static] # %s:%s:%s\n",
						pos.name, frag.id, conf.id
					)
					for ((ffi, ff) in ffparams.withIndex()) {
						write("%d = [\n", ffi)
						ForcefieldParams.forEachPair(confAtomsByMol, staticAtomsByMol) { cmol, catom, smol, satom, dist ->
							ff.pairParams(cmol, catom, smol, satom, dist)?.let { params ->
								write("\t[ %2d, %6d, %6d ], # %s, %s\n",
									confAtomIndices.getValue(catom),
									staticAtomIndices.getValue(satom), // TODO: error here
									paramsCaches[ffi].index(params),
									catom.name,
									staticAtomNames.getValue(satom)
								)
							}
						}
						write("]\n")
					}

					// write the pos-pos params
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
							for ((ffi, ff) in ffparams.withIndex()) {
								write("%d = [\n", ffi)
								ForcefieldParams.forEachPair(confAtomsByMol, posbAtomsByMol) { mola, atoma, molb, atomb, dist ->
									ff.pairParams(mola, atoma, molb, atomb, dist)?.let { params ->

										val atombi = fragb.atoms
											.indexOfFirst { it.name == atomb.name }
											.takeIf { it >= 0 }
											?: throw RuntimeException("can't find atom $atomb in fragment $fragb")

										write("\t[ %2d, %2d, %6d ], # %s, %s\n",
											confAtomIndices.getValue(atoma),
											atombi,
											paramsCaches[ffi].index(params),
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
			for (ffi in ffparams.indices) {
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

		} catch (t: Throwable) {

			t.printStackTrace(System.err)
			error(t.message ?: "Error")

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
