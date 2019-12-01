package edu.duke.cs.ospreygui.compiler

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.tools.*
import edu.duke.cs.ospreygui.motions.dihedralAngle
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.tools.pairs
import org.joml.Vector3d
import kotlin.collections.ArrayList


/**
 * Collects all the information from a conformation space,
 * combines it with forcefield parameters,
 * and emits a TOML file that describes the parameterized
 * conformation space to Osprey in a totally unambiguous way.
 */
class ConfSpaceCompiler(val confSpace: ConfSpace) {

	private val _forcefields: MutableList<ForcefieldParams> = ArrayList()
	val forcefields: List<ForcefieldParams> get() = _forcefields

	fun addForcefield(ff: Forcefield): ForcefieldParams {

		if (forcefields.any { it.forcefield === ff }) {
			throw IllegalArgumentException("forcefield $ff alread added")
		}

		val parameterizer = ff.parameterizer()
		_forcefields.add(parameterizer)
		return parameterizer
	}

	val netCharges = NetCharges()

	data class Report(
		val warnings: List<CompilerWarning>,
		val error: CompilerError?,
		val compiled: CompiledConfSpace?
	)

	/**
	 * Compiles the conf space and the forcefields.
	 * If something goes wrong, the errors are collected and returned in the compilation report.
	 *
	 * Compilation is actually performed in a separate thread,
	 * but progress can be monitored via the returned progress object.
	 */
	fun compile(molLocker: MoleculeLocker = MoleculeLocker()): CompilerProgress {

		// get stable orders for all the positions and conformations
		val confSpaceIndex = ConfSpaceIndex(confSpace)

		// make a dummy thread variable for now,
		// so the compiler progress can access it later
		// otherwise we get an intractable dependency cycle
		var thread = null as Thread?

		val numSingles = confSpaceIndex.positions.sumBy { it.confs.size }
		val numPairs = confSpaceIndex.positions.pairs().sumBy { (posInfo1, posInfo2) ->
			posInfo1.confs.size*posInfo2.confs.size
		}

		// init the progress
		val paramsTask = CompilerProgress.Task(
			"Parameterize molecules",
			forcefields.size*(numSingles + confSpaceIndex.mols.size)
		)
		val fixedAtomsTask = CompilerProgress.Task(
			"Partition fixed atoms",
			forcefields.size*numSingles + 2
		)
		val staticEnergiesTask = CompilerProgress.Task(
			"Calculate energy of static atoms",
			forcefields.size
		)
		val atomPairsTask = CompilerProgress.Task(
			"Calculate forcefield atom pairs",
			forcefields.size*(numSingles*2 + numPairs)
		)
		val progress = CompilerProgress(
			paramsTask, fixedAtomsTask, staticEnergiesTask, atomPairsTask,
			threadGetter = { thread ?: throw Error("compilation thread not created yet") }
		)

		// make a thread to do the actual compilation and start it
		thread = Thread {
			compile(
				molLocker, confSpaceIndex, progress,
				paramsTask, fixedAtomsTask, staticEnergiesTask, atomPairsTask
			)
		}.apply {
			name = "ConfSpaceCompiler"
			isDaemon = false
			start()
		}

		return progress
	}

	private fun compile(
		molLocker: MoleculeLocker,
		confSpaceIndex: ConfSpaceIndex,
		progress: CompilerProgress,
		paramsTask: CompilerProgress.Task,
		fixedAtomsTask: CompilerProgress.Task,
		staticEnergiesTask: CompilerProgress.Task,
		atomPairsTask: CompilerProgress.Task
	) {

		val warnings = ArrayList<CompilerWarning>()

		try {

			// compile the forcefield metadata and settings
			val forcefieldInfos = forcefields.map { ff ->
				CompiledConfSpace.ForcefieldInfo(
					ff.forcefield.name,
					ff.forcefield.ospreyImplementation,
					ff.settings().toList()
				)
			}

			// TODO: issues warnings/errors for:
			//   missing forcefield params

			// TODO: this is kinda slow, especially forcefield parameterization
			//   need to show progress information to the user somehow

			// compute all the forcefield parameters for the conf space
			val params = MolsParams(confSpaceIndex)
			for (mol in confSpaceIndex.mols) {

				// NOTE: copy molecules before sending to the parameterizers,
				// since the parameterizers will store them in the returned MolParams,
				// but we'll change this molecue instance when we set conformations at design positions

				// first, parameterize the molecule without any conformational changes
				for (ff in forcefields) {
					try {
						params[ff, mol] = ff.parameterize(mol.copy(), netCharges[mol]?.netChargeOrThrow)
						paramsTask.increment()
					} catch (t: Throwable) {
						throw CompilerError("Can't parameterize wild-type molecule: $mol", cause = t)
					}
				}

				// then parameterize the molecule for each conformation at each design position
				/* NOTE: SQM consumes atomic coords as input,
					so we should parameterize each conformation,
					rather than each fragment at a design position,
					since theoretically, we could get different partial charges for different conformations
				*/
				// TODO: test whether the coords actually affect the charges?? maybe key by pos/frag instead of pos/conf?
				for (posInfo in confSpaceIndex.positions.filter { it.pos.mol === mol }) {
					posInfo.forEachConf(molLocker) { confInfo ->

						for (ff in forcefields) {
							try {
								params[ff, confInfo] = ff.parameterize(mol.copy(), netCharges[mol]?.getOrThrow(posInfo.pos, confInfo.frag))
								paramsTask.increment()
							} catch (t: Throwable) {
								throw CompilerError("Can't parameterize molecule: $mol with ${posInfo.pos.name} = ${confInfo.id}", cause = t)
							}
						}
					}
				}
			}

			// analyze all the fixed atoms in the conf space
			val fixedAtoms = FixedAtoms(confSpaceIndex, confSpace.fixedAtoms())

			// find the dynamic atoms by comparing conf forcefield params against the wild-type mol params
			for (ff in forcefields) {
				for (posInfo in confSpaceIndex.positions) {
					for (confInfo in posInfo.confs) {

						val changedAtoms = ForcefieldParams.filterChangedAtoms(
							fixedAtoms.fixed(posInfo.pos.mol),
							params[ff, confInfo],
							params[ff, posInfo.pos.mol]
						)

						try {
							fixedAtoms[posInfo].addDynamic(changedAtoms, confInfo)
						} catch (ex: FixedAtoms.ClaimedAtomException) {

							// convert the exception into a user-friendly compiler error
							// TODO: this error won't trip until we get two nearby design positions on a small molecule
							throw CompilerError("""
								|Forcefield parameterization yielded conflicting parameters for atom ${ex.atom}:
								|${ex.confInfo.posInfo.pos.name}:${ex.confInfo.id}: ${params[ff, ex.confInfo].findAtomDescription(ex.atom)}}
								|${posInfo.pos.name}:${confInfo.id}: ${params[ff, confInfo].findAtomDescription(ex.atom)}
							""".trimMargin())
						}

						fixedAtomsTask.increment()
					}
				}
			}

			// all the rest of the fixed atoms are static fixed atoms
			fixedAtoms.updateStatic()
			fixedAtomsTask.increment()

			// compile the static atoms
			val staticAtoms = fixedAtoms.statics
				.map { it.atom.compile(it.name) }
			fixedAtomsTask.increment()

			// calculate the internal energies for the static atoms
			val staticEnergies = forcefields
				.map { ff ->
					ff.calcEnergy(fixedAtoms.staticAtomsByMol, params.getWildTypeByMol(ff))
						.also { staticEnergiesTask.increment() }
				}

			// compile the design positions
			val posInfos = ArrayList<CompiledConfSpace.PosInfo>()
			for (posInfo in confSpaceIndex.positions) {

				// compile the conformations
				val confInfos = ArrayList<CompiledConfSpace.ConfInfo>()
				posInfo.forEachConf(molLocker) { confInfo ->
					confInfos.add(confInfo.compile(fixedAtoms, params))
				}

				posInfos.add(CompiledConfSpace.PosInfo(
					posInfo.pos.name,
					confInfos
				))
			}

			// TODO: push the ff loop inside so the GUI looks prettier

			// compile all the atom pairs for the forcefields
			val atomPairs = forcefields.map {
				AtomPairs(confSpaceIndex, forcefields)
			}
			for (posInfo1 in confSpaceIndex.positions) {
				posInfo1.forEachConf(molLocker) { confInfo1 ->

					// compile the pos atom pairs
					for ((ff, pairs) in forcefields.zip(atomPairs)) {
						pairs.singles[posInfo1.index, confInfo1.index] = compileAtomPairs(
							confInfo1, confInfo1,
							ff, params, fixedAtoms, pairs.paramsCache
						)
						atomPairsTask.increment()
					}

					// compile the pos-static atom pairs
					for ((ff, pairs) in forcefields.zip(atomPairs)) {
						pairs.statics[posInfo1.index, confInfo1.index] = compileAtomPairs(
							confInfo1, fixedAtoms,
							ff, params, pairs.paramsCache
						)
						atomPairsTask.increment()
					}

					for (posInfo2 in confSpaceIndex.positions.subList(0, posInfo1.index)) {
						posInfo2.forEachConf(molLocker) { confInfo2 ->

							// compile the pos-pos atom pairs
							for ((ff, pairs) in forcefields.zip(atomPairs)) {
								pairs.pairs[posInfo1.index, confInfo1.index, posInfo2.index, confInfo2.index] = compileAtomPairs(
									confInfo1, confInfo2,
									ff, params, fixedAtoms, pairs.paramsCache
								)
								atomPairsTask.increment()
							}
						}
					}
				}
			}

			// if we made it this far, return a successful compiler result
			progress.report = Report(
				warnings,
				null,
				CompiledConfSpace(
					confSpace.name,
					forcefieldInfos,
					staticAtoms,
					staticEnergies,
					posInfos,
					atomPairs
				)
			)

		} catch (t: Throwable) {

			// wrap the exception in a compiler error if needed
			val error = t as? CompilerError
				?: CompilerError("Error", null, t)

			// return a report with only warnings and errors, but no compiled conf space
			progress.report = Report(warnings, error, null)
		}
	}

	/**
	 * Compiles the conformation
	 */
	private fun ConfSpaceIndex.ConfInfo.compile(fixedAtoms: FixedAtoms, params: MolsParams): CompiledConfSpace.ConfInfo {

		// collect the atoms for this conf (including dynamic fixed atoms), and assign indices
		val confAtoms = AtomIndex(orderAtoms(fixedAtoms[posInfo].dynamics))

		// compile the continuous motions, if any
		val motions =
			posInfo.posConfSpace.motionSettings[frag]
				?.let { settings ->
					frag.motions.mapNotNull { it.compile(this, settings, confAtoms, fixedAtoms) }
				}
				?: emptyList()

		// compute the internal energies of the conformation atoms
		val internalEnergies = forcefields.map { ff ->
			confAtoms
				.mapNotNull { atom -> ff.internalEnergy(params[ff, this], atom) }
				.sum()
		}

		return CompiledConfSpace.ConfInfo(
			id = id,
			type = frag.type,
			atoms = confAtoms.map { it.compile() },
			motions = motions,
			internalEnergies = internalEnergies
		)
	}

	/**
	 * Compiles the continuous motion
	 */
	private fun ConfLib.ContinuousMotion.compile(
		confInfo: ConfSpaceIndex.ConfInfo,
		settings: ConfSpace.PositionConfSpace.MotionSettings,
		confAtoms: AtomIndex,
		fixedAtoms: FixedAtoms
	): CompiledConfSpace.MotionInfo? {
		when (this) {

			is ConfLib.ContinuousMotion.DihedralAngle -> {

				// filter out h-group rotations when needed
				val isHGroupRotation = affectedAtoms(confInfo.frag)
					.let { atoms ->
						atoms.isNotEmpty() && atoms.all { it.element == Element.Hydrogen }
					}
				if (isHGroupRotation && !settings.includeHGroupRotations) {
					return null
				}

				// make the dihedral angle on the molecule
				val dihedral = confInfo.posInfo.pos.dihedralAngle(this)
				val initialDegrees = dihedral.measureDegrees()

				// compile the dihedral angle
				return CompiledConfSpace.MotionInfo.DihedralAngle(
					minDegrees = initialDegrees - settings.dihedralRadiusDegrees,
					maxDegrees = initialDegrees + settings.dihedralRadiusDegrees,
					abcd = listOf(dihedral.a, dihedral.b, dihedral.c, dihedral.d)
						.map { confAtoms.getOrStatic(it, fixedAtoms) },
					rotated = dihedral.rotatedAtoms
						.map { confAtoms.getOrThrow(it) }
				)
			}
		}
	}

	private fun Atom.compile(name: String = this.name): CompiledConfSpace.AtomInfo =
		CompiledConfSpace.AtomInfo(
			name,
			Vector3d(pos.checkForErrors(name))
		)

	/**
	 * Compile atom pairs for pos and pos-pos interactions.
	 */
	private fun compileAtomPairs(
		confInfo1: ConfSpaceIndex.ConfInfo,
		confInfo2: ConfSpaceIndex.ConfInfo,
		ff: ForcefieldParams,
		params: MolsParams,
		fixedAtoms: FixedAtoms,
		paramsCache: AtomPairs.ParamsCache
	) = ArrayList<AtomPairs.AtomPair>().apply {

		val conf1Atoms = AtomIndex(confInfo1.orderAtoms(fixedAtoms[confInfo1.posInfo].dynamics))
		val conf1AtomsByMol = identityHashMapOf(confInfo1.posInfo.pos.mol to conf1Atoms as List<Atom>)
		val mol1Params = params[ff, confInfo1]

		val conf2Atoms = AtomIndex(confInfo2.orderAtoms(fixedAtoms[confInfo2.posInfo].dynamics))
		val conf2AtomsByMol = identityHashMapOf(confInfo2.posInfo.pos.mol to conf2Atoms as List<Atom>)
		val mol2Params = params[ff, confInfo2]

		ForcefieldParams.forEachPair(conf1AtomsByMol, conf2AtomsByMol) { mol1, atom1, mol2, atom2, dist ->
			ff.pairParams(mol1Params, atom1, mol2Params, atom2, dist)?.let { params ->
				add(AtomPairs.AtomPair(
					conf1Atoms.getOrThrow(atom1),
					conf2Atoms.getOrThrow(atom2),
					paramsCache.index(params.list)
				))
			}
		}
	}

	/**
	 * Compile atom pairs for pos-static interactions
	 */
	private fun compileAtomPairs(
		confInfo: ConfSpaceIndex.ConfInfo,
		fixedAtoms: FixedAtoms,
		ff: ForcefieldParams,
		params: MolsParams,
		paramsCache: AtomPairs.ParamsCache
	) = ArrayList<AtomPairs.AtomPair>().apply {

		val confAtoms = AtomIndex(confInfo.orderAtoms(fixedAtoms[confInfo.posInfo].dynamics))
		val confAtomsByMol = identityHashMapOf(confInfo.posInfo.pos.mol to confAtoms as List<Atom>)
		val confParams = params[ff, confInfo]

		val staticAtomsByMol = fixedAtoms.staticAtomsByMol

		ForcefieldParams.forEachPair(confAtomsByMol, staticAtomsByMol) { cmol, catom, smol, satom, dist ->

			val staticParams = params[ff, smol]

			ff.pairParams(confParams, catom, staticParams, satom, dist)?.let { params ->
				add(AtomPairs.AtomPair(
					confAtoms.getOrThrow(catom),
					fixedAtoms.getStatic(satom).index,
					paramsCache.index(params.list)
				))
			}
		}
	}
}
