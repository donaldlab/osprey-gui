package edu.duke.cs.ospreygui.io

import cuchaz.kludge.tools.x
import cuchaz.kludge.tools.y
import cuchaz.kludge.tools.z
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.tools.associateIdentity
import edu.duke.cs.molscope.tools.identityHashMapOf
import edu.duke.cs.molscope.tools.identityHashSet
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.forcefield.amber.findTypeOrThrow
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.prep.DesignPosition
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


	inner class NetCharges(val smallMol: Molecule) {

		/** The net charge of the unmodified molecule */
		var netCharge: Int? = null

		val netChargeOrThrow get() =
			netCharge ?: throw IllegalStateException("small molecule $smallMol needs a net charge")

		private val charges = IdentityHashMap<DesignPosition,IdentityHashMap<ConfLib.Fragment,Int>>()

		operator fun get(pos: DesignPosition, frag: ConfLib.Fragment) =
			charges.getOrPut(pos) { IdentityHashMap() }.get(frag)

		fun getOrThrow(pos: DesignPosition, frag: ConfLib.Fragment): Int =
			this[pos, frag]
				?: throw NoSuchElementException("small molecule $smallMol needs a net charge for fragment ${frag.id} at design position ${pos.name}")

		operator fun set(pos: DesignPosition, frag: ConfLib.Fragment, charge: Int?) {
			charges.getOrPut(pos) { IdentityHashMap() }.let {
				if (charge != null) {
					it[frag] = charge
				} else {
					it.remove(frag)
				}
			}
		}
	}
	val smallMolNetCharges: MutableMap<Molecule,NetCharges> = IdentityHashMap()

	private fun getSmallMolNetChargesWhenNeeded(mol: Molecule): NetCharges? =
		when (mol.findTypeOrThrow()) {

			MoleculeType.SmallMolecule -> {
				// small molecule, need net charges
				smallMolNetCharges[mol]
					?: throw NoSuchElementException("Small molecule $mol has no net charges configured")
			}

			// not a small molecule, no net charges needed
			else -> null
		}

	/**
	 * Get the net charges for small molecules before any conformational changes.
	 */
	private fun getSmallMolNetCharges(mols: List<Molecule>) =
		IdentityHashMap<Molecule,Int>().apply {

			for (mol in mols) {
				getSmallMolNetChargesWhenNeeded(mol)?.let { netCharges ->
					this[mol] = netCharges.netChargeOrThrow
				}
			}
		}

	/**
	 * Get the net charges for small molecules after setting a
	 * design position to a conformation from the given fragment.
	 */
	private fun getSmallMolNetCharges(mols: List<Molecule>, pos: DesignPosition, frag: ConfLib.Fragment) =
		IdentityHashMap<Molecule,Int>().apply {

			// start with the original net charges
			putAll(getSmallMolNetCharges(mols))

			getSmallMolNetChargesWhenNeeded(pos.mol)?.let { netCharges ->
				// overwrite with the modified net charge
				this[pos.mol] = netCharges.getOrThrow(pos, frag)
			}
		}

	/**
	 * Get the net charges for small molecules after setting two
	 * design positions to conformations from the given fragments.
	 */
	private fun getSmallMolNetCharges(mols: List<Molecule>, pos1: DesignPosition, frag1: ConfLib.Fragment, pos2: DesignPosition, frag2: ConfLib.Fragment) =
		IdentityHashMap<Molecule,Int>().apply {

			// start with the original net charges
			putAll(getSmallMolNetCharges(mols))

			// do the two positions modify the same molecule?
			if (pos1.mol === pos2.mol) {

				// yup, get the net charge for both changes at once
				val mol = pos1.mol
				getSmallMolNetChargesWhenNeeded(mol)?.let { netCharges ->

					// overwrite with the modified net charge
					val charge1 = netCharges.getOrThrow(pos1, frag1)
					val charge2 = netCharges.getOrThrow(pos2, frag2)
					this[mol] = charge1 + charge2 - 2*netCharges.netChargeOrThrow
				}

			} else {

				// nope, apply the two charges separately
				getSmallMolNetChargesWhenNeeded(pos1.mol)?.let { netCharges ->
					this[pos1.mol] = netCharges.getOrThrow(pos1, frag1)
				}
				getSmallMolNetChargesWhenNeeded(pos2.mol)?.let { netCharges ->
					this[pos2.mol] = netCharges.getOrThrow(pos2, frag2)
				}
			}
		}


	data class Message(
		val type: Type,
		val message: String,
		val extraInfo: String? = null
	) {

		enum class Type {
			Warning,
			Error
		}

		override fun toString() =
			"$type: $message"
	}

	private class CompilerError(val msg: String, val extraInfo: String? = null) : RuntimeException(msg)

	data class Report(
		val messages: List<Message>,
		val toml: String?
	) {

		val warnings get() = messages.filter { it.type == Message.Type.Warning }
		val errors get() = messages.filter { it.type == Message.Type.Error }
	}

	/**
	 * Add extra context to exceptions,
	 * so the compiler errors are more useful.
	 */
	inline fun <R> infoOnException(info: String, block: () -> R): R {
		try {
			return block()
		} catch (t: Throwable) {
			throw RuntimeException(info, t)
		}
	}

	/**
	 * Compiles the conf space and the forcefields.
	 * Vigorously throws errors if something goes wrong.
	 */
	fun compile(): Report {

		val buf = StringBuilder()
		fun write(str: String, vararg args: Any) = buf.append(String.format(str, *args))

		val messages = ArrayList<Message>()
		fun warn(msg: String) =
			messages.add(Message(Message.Type.Warning, msg))

		fun Vector3dc.checkForErrors(source: String): Vector3dc = apply {
			// check that all the coords are valid (ie, not NaN)
			if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
				throw CompilerError("Coordinates at '$source' have bad values: [%12.6f,%12.6f,%12.6f]".format(x, y, z))
			}
		}

		try {

			write("\n")
			write("name = %s\n", confSpace.name.quote())
			write("forcefields = [ %s ]\n",
				forcefields.joinToString(", ") { it.name.toLowerCase().quote() }
			)

			// TODO: issues warnings/errors for:
			//   missing forcefield params

			// TODO: setMolecules() is pretty slow for amber,
			//   need to show progress information to the user somehow

			// get the authoritative list of conf space positions in order
			val positions = confSpace.positions()

			// find out what fixed atoms have ff params that are affected by changes in conformations
			// and add them to the atoms list for all the conformations at that design position
			// and also subtract them from the list of fixed atoms

			// first, analyze the current atoms
			val allMols = confSpace.mols
				.map { (_, mol) -> mol }
			val fixedAtoms = confSpace.fixedAtoms()
			val analyses = ffparams
				.associateWith { ff ->

					// setMolecules() is especially error-prone since it does most of the forcefield parameterization
					infoOnException("Can't parameterize original molecules") {
						ff.setMolecules(allMols, getSmallMolNetCharges(allMols))
					}

					ff.analyze(fixedAtoms)
				}

			// then look through all the confs to find the changed atoms
			// TODO: partial charges vary a lot for small molecules in different conformations!
			//   need to limit the change propagation somehow,
			//   otherwise dynamic fixed atoms from multiple design positions will probably overlap!
			val dynamicFixedAtoms = positions
				.associateWith { pos ->
					val changedAtoms = identityHashSet<Atom>()
					confSpace.forEachConf(pos) { frag, conf ->
						for ((ff, analysis) in analyses) {

							// setMolecules() is especially error-prone since it does most of the forcefield parameterization
							infoOnException("Can't parameterize molecules when ${pos.name} is ${frag.id}:${conf.id}") {
								ff.setMolecules(allMols, getSmallMolNetCharges(allMols, pos, frag))
							}

							ff.analyze(fixedAtoms)
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
						atom.pos.checkForErrors(atomName).toToml(),
						staticAtomNames.getValue(atom).quote(),
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
						write("\t{ xyz = %s, name = %8s }, # %d\n",
							atom.pos.checkForErrors("frag=${frag.id}, conf=${conf.id}, atom=${atom.name} after alignment").toToml(),
							atom.name.quote(),
							atomi
						)
					}
					write("]\n")

					// write the internal energy
					write("[pos.$posi.conf.$confi.energy] # %s:%s:%s\n",
						pos.name, frag.id, conf.id
					)
					for ((ffi, ff) in ffparams.withIndex()) {

						// setMolecules() is especially error-prone since it does most of the forcefield parameterization
						infoOnException("Can't parameterize molecules when ${pos.name} is ${frag.id}:${conf.id}") {
							ff.setMolecules(listOf(pos.mol), getSmallMolNetCharges(allMols, pos, frag))
						}

						write("%d = %f\n",
							ffi,
							confAtoms
								.mapNotNull { atom -> ff.internalEnergy(pos.mol, atom) }
								.sum()
						)
					}

					val confAtomsByMol = identityHashMapOf(pos.mol to confAtoms.toList())

					// write the pos-static params
					write("[pos.$posi.conf.$confi.params.static] # %s:%s:%s\n",
						pos.name, frag.id, conf.id
					)
					for ((ffi, ff) in ffparams.withIndex()) {

						// setMolecules() is especially error-prone since it does most of the forcefield parameterization
						infoOnException("Can't parameterize molecules when ${pos.name} is ${frag.id}:${conf.id}") {
							ff.setMolecules(allMols, getSmallMolNetCharges(allMols, pos, frag))
						}

						write("%d = [\n", ffi)
						ForcefieldParams.forEachPair(confAtomsByMol, staticAtomsByMol) { cmol, catom, smol, satom, dist ->
							ff.pairParams(cmol, catom, smol, satom, dist)?.let { params ->
								write("\t[ %2d, %6d, %6d ], # %s, %s\n",
									confAtomIndices.getValue(catom),
									staticAtomIndices.getValue(satom),
									paramsCaches[ffi].index(params.list),
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

								// setMolecules() is especially error-prone since it does most of the forcefield parameterization
								infoOnException("Can't parameterize molecules when ${pos.name} is ${frag.id}:${conf.id} and ${posb.name} is ${fragb.id}:${confb.id}") {
									ff.setMolecules(listOf(pos.mol, posb.mol), getSmallMolNetCharges(allMols, pos, frag, posb, fragb))
								}

								ForcefieldParams.forEachPair(confAtomsByMol, posbAtomsByMol) { mola, atoma, molb, atomb, dist ->
									ff.pairParams(mola, atoma, molb, atomb, dist)?.let { params ->

										val atombi = fragb.atoms
											.indexOfFirst { it.name == atomb.name }
											.takeIf { it >= 0 }
											?: throw RuntimeException("can't find atom $atomb in fragment $fragb")

										write("\t[ %2d, %2d, %6d ], # %s, %s\n",
											confAtomIndices.getValue(atoma),
											atombi,
											paramsCaches[ffi].index(params.list),
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

			// TODO: write the DoFs

		} catch (err: CompilerError) {

			messages.add(Message(Message.Type.Error, err.msg, err.extraInfo))

		} catch (t: Throwable) {

			t.printStackTrace(System.err)

			// collect all the error messages from all the exceptions in the chain
			val msgs = ArrayList<String>()
			var cause: Throwable? = t.cause
			while (cause != null) {
				msgs.add(cause.message ?: cause.javaClass.simpleName)
				cause = cause.cause
			}
			messages.add(Message(
				Message.Type.Error,
				t.message ?: t.javaClass.simpleName,
				msgs.joinToString("\n")
			))

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
