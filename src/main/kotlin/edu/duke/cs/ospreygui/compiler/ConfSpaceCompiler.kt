package edu.duke.cs.ospreygui.compiler

import cuchaz.kludge.tools.toRadians
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.tools.*
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import edu.duke.cs.ospreygui.motions.*
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.tools.UnsupportedClassException
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

		val numSingles = confSpaceIndex.positions.sumBy { it.fragments.size }
		val numPairs = confSpaceIndex.positions.pairs().sumBy { (posInfo1, posInfo2) ->
			posInfo1.fragments.size*posInfo2.fragments.size
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
			numSingles*2 + numPairs
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

			// TODO: optimize this!

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

				// then parameterize the molecule for each fragment at each design position
				for (posInfo in confSpaceIndex.positions.filter { it.pos.mol === mol }) {
					posInfo.forEachFrag(molLocker) { fragInfo, confInfo ->

						for (ff in forcefields) {
							try {
								params[ff, fragInfo] = ff.parameterize(mol.copy(), netCharges[mol]?.getOrThrow(posInfo.pos, confInfo.fragInfo.frag))
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

					val wtParams = params[ff, posInfo.pos.mol]
					val molToWT = posInfo.pos.mol.mapAtomsByValueTo(wtParams.mol)
					val molFixedAtoms = fixedAtoms.fixed(posInfo.pos.mol)

					for (fragInfo in posInfo.fragments) {

						val fragParams = params[ff, fragInfo]
						val molToFrag = posInfo.pos.mol.mapAtomsByValueTo(fragParams.mol)

						// find the atoms whose parameters have changed
						val changedAtoms = molFixedAtoms
							.filter { atom ->
								wtParams[molToWT.getBOrThrow(atom)] != fragParams[molToFrag.getBOrThrow(atom)]
							}

						try {
							fixedAtoms[posInfo].addDynamic(changedAtoms, fragInfo)
						} catch (ex: FixedAtoms.ClaimedAtomException) {

							// convert the exception into a user-friendly compiler error
							val fixedName = ex.atom.fixedName(posInfo.pos.mol)

							val newDesc = molToFrag
								.getB(ex.atom)
								?.let { fragParams[it] }

							val oldParams = params[ff, ex.fragInfo]
							val oldDesc = posInfo.pos.mol
								.mapAtomsByValueTo(oldParams.mol)
								.getB(ex.atom)
								?.let { oldParams[it] }

							throw CompilerError("""
								|Forcefield parameterization of fragments at different design positions yielded conflicting parameters for a fixed atom:
								|fixed atom=$fixedName, position=${ex.fragInfo.posInfo.pos.name}, fragment=${ex.fragInfo.frag.id}, $oldDesc
								|fixed atom=$fixedName, position=${posInfo.pos.name}, fragment=${fragInfo.frag.id}, $newDesc
							""".trimMargin())
						}

						fixedAtomsTask.increment()
					}
				}
			}

			// all the rest of the fixed atoms are static fixed atoms
			fixedAtoms.updateStatic()
			fixedAtomsTask.increment()

			// compile molecule motions
			val molInfos = confSpaceIndex.mols.map { mol ->
				CompiledConfSpace.MolInfo(
					mol.name,
					mol.type,
					confSpace.molMotions[mol]?.map { it.compile(mol) } ?: emptyList()
				)
			}

			// prep for atom compilation
			val infoIndexer = InfoIndexer(confSpaceIndex.mols.zip(molInfos))

			// compile the static atoms
			val staticAtoms = fixedAtoms.statics
				.map { it.atom.compile(infoIndexer, it.mol) }
			fixedAtomsTask.increment()

			// calculate the internal energies for the static atoms
			val staticEnergies = forcefields
				.map { ff ->

					val paramsByMol = params.getWildTypeByMol(ff)
					val staticAtomsByMol = fixedAtoms.staticAtomsByMol

					// map atoms onto the params mols
					val molToParams = staticAtomsByMol
						.mapValuesIdentity { (mol, _) ->
							mol.mapAtomsByNameTo(paramsByMol.getValue(mol).mol)
						}

					ff.calcEnergy(staticAtomsByMol, paramsByMol, molToParams)
						.also { staticEnergiesTask.increment() }
				}

			// compile the design positions
			val posInfos = ArrayList<CompiledConfSpace.PosInfo>()
			for (posInfo in confSpaceIndex.positions) {

				// compile the fragments
				val fragInfos = ArrayList<CompiledConfSpace.FragInfo>()
				posInfo.forEachFrag(molLocker) { fragInfo, confInfo ->
					fragInfos.add(fragInfo.compile(fixedAtoms))
				}

				// compile the conformations
				val confInfos = ArrayList<CompiledConfSpace.ConfInfo>()
				posInfo.forEachConf(molLocker) { confInfo ->
					confInfos.add(confInfo.compile(infoIndexer, fixedAtoms, params))
				}

				posInfos.add(CompiledConfSpace.PosInfo(
					posInfo.pos.name,
					posInfo.pos.type,
					fragInfos,
					confInfos
				))
			}

			// compile all the atom pairs for the forcefields
			val atomPairs = forcefields
				.map { AtomPairs(confSpaceIndex) }
			for (posInfo1 in confSpaceIndex.positions) {
				posInfo1.forEachFrag(molLocker) { fragInfo1, confInfo1 ->

					// compile the pos atom pairs
					atomPairs.compile(confInfo1, fixedAtoms, params)
					atomPairsTask.increment()

					// compile the pos-static atom pairs
					atomPairs.compileStatic(confInfo1, fixedAtoms, params)
					atomPairsTask.increment()

					for (posInfo2 in confSpaceIndex.positions.subList(0, posInfo1.index)) {
						posInfo2.forEachFrag(molLocker) { fragInfo2, confInfo2 ->

							// compile the pos-pos atom pairs
							atomPairs.compile(confInfo1, confInfo2, fixedAtoms, params)
							atomPairsTask.increment()
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
					molInfos,
					infoIndexer.resInfos,
					staticAtoms,
					staticEnergies,
					posInfos,
					atomPairs
				)
			)

		} catch (t: Throwable) {

			// dump the error to the console, just in case any developers are watching
			t.printStackTrace(System.err)

			// wrap the exception in a compiler error if needed
			val error = t as? CompilerError
				?: CompilerError("Error", null, t)

			// return a report with only warnings and errors, but no compiled conf space
			progress.report = Report(warnings, error, null)
		}
	}

	private class InfoIndexer(val molInfos: List<Pair<Molecule,CompiledConfSpace.MolInfo>>) {

		val resInfos = ArrayList<CompiledConfSpace.ResInfo>()

		fun indexOfMol(mol: Molecule): Int =
			molInfos
				.indexOfFirst { (m, _) -> m === mol }
				.takeIf { it >= 0 }
				?: throw IllegalArgumentException("molecule has no molecule info: $mol")

		fun indexOfRes(chain: Polymer.Chain, res: Polymer.Residue): Int {

			val indexInChain = chain.residues
				.indexOf(res)
				.takeIf { it >= 0 }
				?: throw IllegalArgumentException("residue $res is not in chain $chain")

			val info = CompiledConfSpace.ResInfo(chain.id, res.id, res.type, indexInChain)

			var index = resInfos.indexOf(info)
			if (index < 0) {
				index = resInfos.size
				resInfos.add(info)
			}

			return index
		}
	}

	/**
	 * Compiles the motions for this molecule
	 */
	private fun MolMotion.Description.compile(mol: Molecule): CompiledConfSpace.MotionInfo = when (this) {

		is TranslationRotation.MolDescription -> CompiledConfSpace.MotionInfo.TranslationRotation(
			maxTranslationDist,
			maxRotationDegrees.toRadians(),
			centroid = Vector3d().apply {
				for (atom in mol.atoms) {
					add(atom.pos)
				}
				div(mol.atoms.size.toDouble())
			}
		)

		else -> throw UnsupportedClassException("don't know how to compile molecule motion", this)
	}


	/**
	 * Compiles the fragment
	 */
	private fun ConfSpaceIndex.FragInfo.compile(fixedAtoms: FixedAtoms): CompiledConfSpace.FragInfo {

		// collect the atoms for this fragment (including dynamic fixed atoms), and assign indices
		val confAtoms = AtomIndex(orderAtoms(fixedAtoms[posInfo].dynamics))

		return CompiledConfSpace.FragInfo(
			name = frag.id,
			atomNames = confAtoms.map { it.name }
		)
	}

	/**
	 * Compiles the conformation
	 */
	private fun ConfSpaceIndex.ConfInfo.compile(infoIndexer: InfoIndexer, fixedAtoms: FixedAtoms, params: MolsParams): CompiledConfSpace.ConfInfo {

		// collect the atoms for this conf (including dynamic fixed atoms), and assign indices
		val confAtoms = AtomIndex(fragInfo.orderAtoms(fixedAtoms[posInfo].dynamics))

		// compute the internal energies of the conformation atoms
		val internalEnergies = forcefields.map { ff ->
			val fragParams = params[ff, fragInfo]
			val molToFrag = posInfo.pos.mol.mapAtomsByNameTo(fragParams.mol)
			confAtoms
				.mapNotNull { atom -> ff.internalEnergy(params[ff, fragInfo], molToFrag.getBOrThrow(atom)) }
				.sum()
		}

		return CompiledConfSpace.ConfInfo(
			id = id,
			type = fragInfo.frag.type,
			atoms = confAtoms.map { it.compile(infoIndexer, posInfo.pos.mol) },
			fragIndex = fragInfo.index,
			motions = confConfSpace.motions.map { it.compile(confAtoms, fixedAtoms) },
			internalEnergies = internalEnergies
		)
	}

	/**
	 * Compiles the continuous motion
	 */
	private fun ConfMotion.Description.compile(
		confAtoms: AtomIndex,
		fixedAtoms: FixedAtoms
	): CompiledConfSpace.MotionInfo = when (this) {

		is DihedralAngle.ConfDescription -> {

			val a = pos.atomResolverOrThrow.resolveOrThrow(motion.a)
			val b = pos.atomResolverOrThrow.resolveOrThrow(motion.b)
			val c = pos.atomResolverOrThrow.resolveOrThrow(motion.c)
			val d = pos.atomResolverOrThrow.resolveOrThrow(motion.d)

			CompiledConfSpace.MotionInfo.DihedralAngle(
				minDegrees,
				maxDegrees,
				abcd = listOf(a, b, c, d)
					.map { confAtoms.getOrStatic(it, fixedAtoms) },
				rotated = DihedralAngle.findRotatedAtoms(pos.mol, b, c)
					.map { confAtoms.getOrThrow(it) }
			)
		}

		else -> throw UnsupportedClassException("don't know how to compile conformation motion", this)
	}

	private fun Atom.compile(infoIndexer: InfoIndexer, mol: Molecule): CompiledConfSpace.AtomInfo =
		CompiledConfSpace.AtomInfo(
			name,
			Vector3d(pos.checkForErrors(name)),
			infoIndexer.indexOfMol(mol),
			(mol as? Polymer)
				?.findChainAndResidue(this)
				?.let { (chain, res) -> infoIndexer.indexOfRes(chain, res) }
				?: -1
		)

	class MolInfo(
		val mol: Molecule,
		val params: ForcefieldParams.MolParams
	) {
		val molToFrag = mol.mapAtomsByNameTo(this.params.mol)
	}

	/**
	 * Compile atom pairs for pos interactions.
	 */
	private fun List<AtomPairs>.compile(
		confInfo: ConfSpaceIndex.ConfInfo,
		fixedAtoms: FixedAtoms,
		params: MolsParams
	) {
		val confAtoms = AtomIndex(confInfo.fragInfo.orderAtoms(fixedAtoms[confInfo.posInfo].dynamics))
		val confAtomsByMol = identityHashMapOf(confInfo.posInfo.pos.mol to confAtoms as List<Atom>)
		val molInfos = forcefields.map { ff ->
			MolInfo(confInfo.posInfo.pos.mol, params[ff, confInfo.fragInfo])
		}

		ForcefieldParams.forEachPair(confAtomsByMol, confAtomsByMol) { mol1, atom1, mol2, atom2, dist ->

			for ((ffi, ff) in forcefields.withIndex()) {

				// map atoms onto the params mols
				val molInfo = molInfos[ffi]
				val patom1 = molInfo.molToFrag.getBOrThrow(atom1)
				val patom2 = molInfo.molToFrag.getBOrThrow(atom2)

				ff.pairParams(molInfo.params, patom1, molInfo.params, patom2, dist)?.let { params ->
					this[ffi].singles.add(
						confInfo.posInfo.index,
						confInfo.fragInfo.index,
						confAtoms.getOrThrow(atom1),
						confAtoms.getOrThrow(atom2),
						params.list
					)
				}
			}
		}
	}

	/**
	 * Compile atom pairs for pos-pos interactions.
	 */
	private fun List<AtomPairs>.compile(
		confInfo1: ConfSpaceIndex.ConfInfo,
		confInfo2: ConfSpaceIndex.ConfInfo,
		fixedAtoms: FixedAtoms,
		params: MolsParams
	) {
		val conf1Atoms = AtomIndex(confInfo1.fragInfo.orderAtoms(fixedAtoms[confInfo1.posInfo].dynamics))
		val conf1AtomsByMol = identityHashMapOf(confInfo1.posInfo.pos.mol to conf1Atoms as List<Atom>)
		val mol1Infos = forcefields.map { ff ->
			MolInfo(confInfo1.posInfo.pos.mol, params[ff, confInfo1.fragInfo])
		}

		val conf2Atoms = AtomIndex(confInfo2.fragInfo.orderAtoms(fixedAtoms[confInfo2.posInfo].dynamics))
		val conf2AtomsByMol = identityHashMapOf(confInfo2.posInfo.pos.mol to conf2Atoms as List<Atom>)
		val mol2Infos = forcefields.map { ff ->
			MolInfo(confInfo2.posInfo.pos.mol, params[ff, confInfo2.fragInfo])
		}

		ForcefieldParams.forEachPair(conf1AtomsByMol, conf2AtomsByMol) { mol1, atom1, mol2, atom2, dist ->

			for ((ffi, ff) in forcefields.withIndex()) {

				// map atoms onto the params mols
				val mol1Info = mol1Infos[ffi]
				val patom1 = mol1Info.molToFrag.getBOrThrow(atom1)
				val mol2Info = mol2Infos[ffi]
				val patom2 = mol2Info.molToFrag.getBOrThrow(atom2)

				ff.pairParams(mol1Info.params, patom1, mol2Info.params, patom2, dist)?.let { params ->
					this[ffi].pairs.add(
						confInfo1.posInfo.index,
						confInfo1.fragInfo.index,
						confInfo2.posInfo.index,
						confInfo2.fragInfo.index,
						conf1Atoms.getOrThrow(atom1),
						conf2Atoms.getOrThrow(atom2),
						params.list
					)
				}
			}
		}
	}

	/**
	 * Compile atom pairs for pos-static interactions
	 */
	private fun List<AtomPairs>.compileStatic(
		confInfo: ConfSpaceIndex.ConfInfo,
		fixedAtoms: FixedAtoms,
		params: MolsParams
	) {
		val confAtoms = AtomIndex(confInfo.fragInfo.orderAtoms(fixedAtoms[confInfo.posInfo].dynamics))
		val confAtomsByMol = identityHashMapOf(confInfo.posInfo.pos.mol to confAtoms as List<Atom>)
		val confMolInfos = forcefields.map { ff ->
			MolInfo(confInfo.posInfo.pos.mol, params[ff, confInfo.fragInfo])
		}

		val staticAtomsByMol = fixedAtoms.staticAtomsByMol
		val staticMols = staticAtomsByMol.keys.toList()
		val staticMolInfos = forcefields.map { ff ->
			staticMols.associateIdentity { mol ->
				mol to MolInfo(mol, params[ff, mol])
			}
		}

		ForcefieldParams.forEachPair(confAtomsByMol, staticAtomsByMol) { cmol, catom, smol, satom, dist ->

			for ((ffi, ff) in forcefields.withIndex()) {

				// map atoms onto the params mols
				val confMolInfo = confMolInfos[ffi]
				val patom1 = confMolInfo.molToFrag.getBOrThrow(catom)
				val staticMolInfo = staticMolInfos[ffi].getValue(smol)
				val patom2 = staticMolInfo.molToFrag.getBOrThrow(satom)

				ff.pairParams(confMolInfo.params, patom1, staticMolInfo.params, patom2, dist)?.let { params ->
					this[ffi].statics.add(
						confInfo.posInfo.index,
						confInfo.fragInfo.index,
						confAtoms.getOrThrow(catom),
						fixedAtoms.getStatic(satom).index,
						params.list
					)
				}
			}
		}
	}
}
