package edu.duke.cs.ospreygui.io

import cuchaz.kludge.tools.x
import cuchaz.kludge.tools.y
import cuchaz.kludge.tools.z
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.tools.*
import edu.duke.cs.ospreygui.dofs.DihedralAngle
import edu.duke.cs.ospreygui.dofs.dihedralAngle
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

	fun addForcefield(ff: Forcefield): ForcefieldParams {

		if (ff in forcefields) {
			throw IllegalArgumentException("forcefield $ff alread added")
		}

		val parameterizer = ff.parameterizer()
		ffparams.add(parameterizer)
		return parameterizer
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

	private fun getNetCharges(mol: Molecule): NetCharges? =
		when (mol.findTypeOrThrow()) {

			MoleculeType.SmallMolecule -> {
				// small molecule, need net charges
				smallMolNetCharges[mol]
					?: throw NoSuchElementException("Small molecule $mol has no net charges configured")
			}

			// not a small molecule, no net charges needed
			else -> null
		}

	class ForcefieldMolParams(val original: ForcefieldParams.MolParams) {

		private val byConf = IdentityHashMap<DesignPosition,IdentityHashMap<ConfLib.Conf,ForcefieldParams.MolParams>>()

		operator fun get(pos: DesignPosition, conf: ConfLib.Conf) =
			byConf.getValue(pos).getValue(conf)

		operator fun set(pos: DesignPosition, conf: ConfLib.Conf, molParams: ForcefieldParams.MolParams) {
			byConf.getOrPut(pos) { IdentityHashMap() }[conf] = molParams
		}
	}

	class ForcefieldMolsParams {
		private val byMol = IdentityHashMap<Molecule,ForcefieldMolParams>()

		operator fun get(mol: Molecule) =
			byMol.getValue(mol)

		operator fun set(mol: Molecule, ffMolParams: ForcefieldMolParams) {
			byMol[mol] = ffMolParams
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

	fun Vector3dc.checkForErrors(source: String): Vector3dc = apply {
		// check that all the coords are valid (ie, not NaN)
		if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
			throw CompilerError("Coordinates at '$source' have bad values: [%12.6f,%12.6f,%12.6f]".format(x, y, z))
		}
	}

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
	// TODO: this function is getting big and complicated... break it up?
	fun compile(): Report {

		val buf = StringBuilder()
		fun write(str: String, vararg args: Any) = buf.append(String.format(str, *args))

		val messages = ArrayList<Message>()
		fun warn(msg: String) =
			messages.add(Message(Message.Type.Warning, msg))

		try {

			write("\n")
			write("name = %s\n", confSpace.name.quote())
			write("forcefields = [\n")
			for (forcefield in forcefields) {
				write("\t[ %s, %s ],\n",
					forcefield.name.quote(),
					forcefield.ospreyImplementation.quote()
				)
			}
			write("]\n")

			// write out forcefield settings
			for ((ffi, ff) in ffparams.withIndex()) {
				ff.settings()
					.takeIf { it.isNotEmpty() }
					?.let { settings ->
						write("\n")
						write("[ffsettings.$ffi]\n")
						for ((key, value) in settings) {

							val valueToml = when (value) {
								is String -> value.quote()
								else -> value.toString()
							}

							write("%s = %s\n", key, valueToml)
						}
					}
			}

			// TODO: issues warnings/errors for:
			//   missing forcefield params

			// TODO: setMolecules() is pretty slow for amber,
			//   need to show progress information to the user somehow

			val allMols = confSpace.mols
				.map { (_, mol) -> mol }

			val fixedAtoms = confSpace.fixedAtoms()

			// get the authoritative list of conf space positions in order
			val positions = confSpace.positions()

			val molsParamsByFF = ffparams
				.associateIdentity { ff ->
					ff to ForcefieldMolsParams().apply {

						for (mol in allMols) {

							// NOTE: copy molecules before sending to the parameterizers,
							// since the parameterizers will store them in the returned MolParams,
							// but we'll change this molecue instance when we set conformations at design positions

							// first, parameterize the molecule without any conformational changes
							this[mol] = ForcefieldMolParams(
								infoOnException("Can't parameterize original molecule: $mol") {
									ff.parameterize(mol.copy(), getNetCharges(mol)?.netChargeOrThrow)
								}
							).apply {

								// then parameterize the molecule for each conformation at each design position
								/* NOTE: SQM consumes atomic coords as input,
									so we should parameterize each conformation,
									rather than each fragment at a design position,
									since theoretically, we could get different partial charges for different conformations
								*/
								val molPositions = positions.filter { it.mol === mol }
								for (pos in molPositions) {
									confSpace.forEachConf(pos) { frag, conf ->

										this[pos, conf] = infoOnException("Can't parameterize molecule: $mol with ${pos.name} = ${frag.id}:${conf.id}") {
											ff.parameterize(mol.copy(), getNetCharges(mol)?.getOrThrow(pos, frag))
										}
									}
								}
							}
						}
					}
				}

			// find out what fixed atoms have ff params that are affected by changes in conformations
			// and add them to the atoms lists for all the conformations at that design position
			// look through all the molecule params for each conf to find the changed atoms
			// TODO: partial charges vary a lot for small molecules in different conformations!
			//   need to limit the change propagation somehow,
			//   otherwise dynamic fixed atoms from multiple design positions will probably overlap
			val dynamicFixedAtoms = positions
				.associateWith { pos ->

					val mol = pos.mol
					val fixedAtomsForMol = fixedAtoms.getValue(mol)
					val posConfSpace = confSpace.positionConfSpaces[pos]!!

					val changedAtoms = identityHashSet<Atom>()

					for ((_, ffMolsParams) in molsParamsByFF) {
						val ffMolParams = ffMolsParams[mol]

						for ((_, confs) in posConfSpace.confs) {
							for (conf in confs) {
								changedAtoms.addAll(ffMolParams[pos, conf].findChangedAtoms(ffMolParams.original, fixedAtomsForMol))
							}
						}
					}

					// TODO: limit the dynamic fixed atoms by bond distance to the bonded anchor atoms?

					return@associateWith changedAtoms
				}
			fun Atom.isDynamic() =
				dynamicFixedAtoms.values.any { this in it }

			// make sure no two positions share any dynamic fixed atoms
			// TODO: this error won't trip until we get two design positions on the small molecule in the test conf space
			for (i1 in 0 until positions.size) {

				val pos1 = positions[i1]
				val atoms1 = dynamicFixedAtoms.getValue(pos1)

				for (i2 in 0 until i1) {

					val pos2 = positions[i2]

					// get the mol the two positions share, if any
					val mol = if (pos1.mol === pos2.mol) {
						pos1.mol
					} else {
						// no shared mol, so can't share fixed atoms either
						continue
					}

					val atoms2 = dynamicFixedAtoms.getValue(pos2)

					val commonAtoms = atoms1.intersect(atoms2)
					if (commonAtoms.isNotEmpty()) {
						throw CompilerError("""
							|Forcefield parameterization at positions ${pos1.name} and ${pos2.name} yield conflicting parameters for atoms:
							|${commonAtoms.map { it.fixedName(mol) }}
						""".trimMargin())
					}
				}
			}

			// static atoms are the subset of fixed atoms that don't change forcefield params
			// collect all the static atoms
			val staticAtomsByMol = fixedAtoms
				.mapValuesIdentity { (_, atoms) ->
					atoms.filter { !it.isDynamic() }
				}
			fun staticMolParamsByMol(ff: ForcefieldParams) = staticAtomsByMol
				.mapValuesIdentity { (mol, _) ->
					molsParamsByFF.getValue(ff)[mol].original
				}


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

					// assign the atom name
					val atomName = atom.fixedName(mol)
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
					ff.calcEnergy(staticAtomsByMol, staticMolParamsByMol(ff))
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
					write("id = %s\n", "${frag.id}:${conf.id}".quote())
					write("type = %s\n", frag.type.quote())

					// collect the atoms for this conf (including dynamic fixed atoms), and assign indices
					val confAtoms = dynamicFixedAtoms.getValue(pos) +
						frag.atoms.map { pos.atomResolverOrThrow.resolveOrThrow(it) }
					val confAtomIndices = confAtoms
						.withIndex()
						.associateIdentity { (i, atom) -> atom to i }

					// write the atoms for this conf
					write("atoms = [\n")
					for ((atomi, atom) in confAtoms.withIndex()) {
						write("\t{ xyz = %s, name = %8s }, # %d\n",
							atom.pos.checkForErrors("frag=${frag.id}, conf=${conf.id}, atom=${atom.name} after alignment").toToml(),
							atom.name.quote(),
							atomi
						)
					}
					write("]\n")

					// write the DoFs for this conf, if any
					// TODO: all confs from the same fragment share the same DoFs, maybe de-duplicate somehow?
					//  it's not that much space to save though, the DoF descriptions are small (for now?)
					confSpace.positionConfSpaces[pos]?.let { posConfSpace ->
						posConfSpace.dofSettings[frag]?.let { settings ->

							// get the active DoFs for this pos according to the DoFs settings
							val dofs = frag.dofs
								.filter { dof ->
									when (dof) {
										is ConfLib.DegreeOfFreedom.DihedralAngle -> {

											// include h-group rotations when needed
											val isHGroupRotation = dof.affectedAtoms(frag)
												.let { atoms ->
													atoms.isNotEmpty() && atoms.all { it.element == Element.Hydrogen }
												}

											!isHGroupRotation || settings.includeHGroupRotations
										}
									}
								}

							if (dofs.isNotEmpty()) {

								write("motions = [\n")
								for (dof in dofs) {
									when (dof) {
										is ConfLib.DegreeOfFreedom.DihedralAngle -> {

											val dihedral = pos.dihedralAngle(dof)
											val initialDegrees = dihedral.measureDegrees()

											write("\t{ type = %s, bounds = [ %f, %f ], abcd = [ %s ], rotated = [ %s ] },\n",
												"dihedral".quote(),
												initialDegrees - settings.dihedralRadiusDegrees,
												initialDegrees + settings.dihedralRadiusDegrees,
												listOf(dihedral.a, dihedral.b, dihedral.c, dihedral.d)
													.map { confAtomIndices.getValue(it) }
													.joinToString(", ") { it.toString() },
												dihedral.rotatedAtoms
													.map { confAtomIndices.getValue(it) }
													.joinToString(", ") { it.toString() }
											)
										}
									}
								}
								write("]\n")
							}
						}
					}


					// write the internal energy
					write("[pos.$posi.conf.$confi.energy] # %s:%s:%s\n",
						pos.name, frag.id, conf.id
					)
					for ((ffi, ff) in ffparams.withIndex()) {

						val molParams = molsParamsByFF.getValue(ff)[pos.mol][pos, conf]

						write("%d = %f\n",
							ffi,
							confAtoms
								.mapNotNull { atom -> ff.internalEnergy(molParams, atom) }
								.sum()
						)
					}

					// write the pos single params
					write("[pos.$posi.conf.$confi.params.internal] # %s:%s:%s\n",
						pos.name, frag.id, conf.id
					)
					for ((ffi, ff) in ffparams.withIndex()) {

						val molParams = molsParamsByFF.getValue(ff)[pos.mol][pos, conf]
						val confAtomsByMol = identityHashMapOf(pos.mol to confAtoms.toList())

						write("%d = [\n", ffi)
						ForcefieldParams.forEachPair(confAtomsByMol, confAtomsByMol) { mola, atoma, molb, atomb, dist ->
							ff.pairParams(molParams, atoma, molParams, atomb, dist)?.let { params ->
								write("\t[ %2d, %2d, %6d ], # %s, %s\n",
									confAtomIndices.getValue(atoma),
									confAtomIndices.getValue(atomb),
									paramsCaches[ffi].index(params.list),
									atoma.name,
									atomb.name
								)
							}
						}
						write("]\n")
					}

					// write the pos-static pair params
					write("[pos.$posi.conf.$confi.params.static] # %s:%s:%s\n",
						pos.name, frag.id, conf.id
					)
					for ((ffi, ff) in ffparams.withIndex()) {

						val cmolParams = molsParamsByFF.getValue(ff)[pos.mol][pos, conf]
						val confAtomsByMol = identityHashMapOf(pos.mol to confAtoms.toList())

						write("%d = [\n", ffi)
						ForcefieldParams.forEachPair(confAtomsByMol, staticAtomsByMol) { cmol, catom, smol, satom, dist ->

							val smolParams = molsParamsByFF.getValue(ff)[smol].original

							ff.pairParams(cmolParams, catom, smolParams, satom, dist)?.let { params ->
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

					// write the pos-pos pair params
					for (posbi in 0 until posi) {
						val posb = positions[posbi]

						var confbi = 0
						confSpace.forEachConf(posb) { fragb, confb ->

							// collect the atoms for this conf (including dynamic fixed atoms), and assign indices
							val confbAtoms = dynamicFixedAtoms.getValue(posb) +
								fragb.atoms.map { posb.atomResolverOrThrow.resolveOrThrow(it) }
							val confbAtomIndices = confbAtoms
								.withIndex()
								.associateIdentity { (i, atom) -> atom to i }

							write("\n")
							write("[pos.$posi.conf.$confi.params.pos.$posbi.conf.$confbi] # %s:%s:%s - %s:%s:%s\n",
								pos.name, frag.id, conf.id,
								posb.name, fragb.id, confb.id
							)
							for ((ffi, ff) in ffparams.withIndex()) {

								val molaParams = molsParamsByFF.getValue(ff)[pos.mol][pos, conf]
								val confaAtomsByMol = identityHashMapOf(pos.mol to confAtoms.toList())

								val molbParams = molsParamsByFF.getValue(ff)[posb.mol][posb, confb]
								val confbAtomsByMol = identityHashMapOf(posb.mol to confbAtoms.toList())

								write("%d = [\n", ffi)
								ForcefieldParams.forEachPair(confaAtomsByMol, confbAtomsByMol) { mola, atoma, molb, atomb, dist ->
									ff.pairParams(molaParams, atoma, molbParams, atomb, dist)?.let { params ->
										write("\t[ %2d, %2d, %6d ], # %s, %s\n",
											confAtomIndices.getValue(atoma),
											confbAtomIndices.getValue(atomb),
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

				// TODO: these files can get kinda big (several MiB), consider compression? maybe LZMA?
			}
			return Report(messages, toml)
		}
	}
}

private fun String.quote() = "'$this'"

private fun Vector3dc.toToml() =
	"[ %12.6f, %12.6f, %12.6f ]".format(x, y, z)


/**
 * make a globally-unique atom name, since fixed atoms can come from different molecules
 */
private fun Atom.fixedName(mol: Molecule): String {
	val resName = (mol as? Polymer)
		?.let {
			it.findChainAndResidue(this)?.let { (chain, res) ->
				"-${chain.id}${res.id}"
			}
		}
		?: ""
	return "${mol.name}$resName-$name"
}
