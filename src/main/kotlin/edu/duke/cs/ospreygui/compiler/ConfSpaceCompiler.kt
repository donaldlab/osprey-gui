package edu.duke.cs.ospreygui.compiler

import cuchaz.kludge.tools.toRadians
import edu.duke.cs.molscope.molecule.*
import edu.duke.cs.molscope.tools.*
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import edu.duke.cs.ospreygui.motions.*
import edu.duke.cs.ospreygui.prep.Assignments
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.tools.UnsupportedClassException
import edu.duke.cs.ospreygui.tools.pairs
import org.joml.Vector3d
import java.util.*
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
	fun compile(): CompilerProgress {

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
			compile(confSpaceIndex, progress, paramsTask, fixedAtomsTask, staticEnergiesTask, atomPairsTask)
		}.apply {
			name = "ConfSpaceCompiler"
			isDaemon = false
			start()
		}

		return progress
	}

	private fun compile(
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
			// TODO: this is waaaay more complicated than it needs to be, could simplify a lot!
			//   need a kind of molecule/atom identification system that is aware of assignments
			//   then we wouldn't need to keep so many copies of molecules everywhere
			//   maybe upgrade one of the conf space or atom indices to do the job?

			// compute all the forcefield parameters for the conf space
			val params = MolsParams(confSpaceIndex)
			for (mol in confSpaceIndex.mols) {

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
				// TODO: ffparams keep their own molecule copies, which must be mapped later... any way to simplify that?? maybe remove some copies and mappings?
				// TODO: parallelize this
				for (posInfo in confSpaceIndex.positions.filter { it.pos.mol === mol }) {
					posInfo.forEachFrag { assignmentInfo, confInfo ->
						for (ff in forcefields) {
							try {
								params[ff, confInfo.fragInfo] = ff.parameterize(assignmentInfo.mol, netCharges[mol]?.getOrThrow(posInfo.pos, confInfo.fragInfo.frag))
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

			// index the molecules and residues
			val infoIndexer = InfoIndexer(confSpaceIndex.mols)

			// compile the static atoms
			val staticAtoms = fixedAtoms.statics
				.map { it.atom.compile(infoIndexer, it.mol) }
			fixedAtomsTask.increment()

			// compile the molecule motions
			for ((moli, mol) in confSpaceIndex.mols.withIndex()) {
				val motions = infoIndexer.molInfos[moli].motions
				for (description in confSpace.molMotions.getOrDefault(mol, mutableListOf())) {
					motions.add(description.compile(mol, fixedAtoms))
				}
			}

			// collect the atoms affected by molecule motions
			val staticAffectedAtomsByMol = confSpaceIndex.mols
				.associateIdentity { mol ->
					val atoms = confSpace.molMotions[mol]
						?.flatMap { desc -> desc.getAffectedAtoms() }
						?: emptyList()
					mol to atoms.toIdentitySet()
				}

			// calculate the internal energies for the static atoms that aren't affected by molecule motions
			val staticUnaffectedAtomsByMol = fixedAtoms.staticAtomsByMol
				.mapValuesIdentity { (mol, atoms) ->
					atoms.filter { it !in staticAffectedAtomsByMol.getOrDefault(mol, mutableSetOf()) }
				}
			val staticEnergies = forcefields
				.map { ff ->

					val paramsByMol = params.getWildTypeByMol(ff)

					// map atoms onto the params mols
					val molToParams = staticUnaffectedAtomsByMol
						.mapValuesIdentity { (mol, _) ->
							mol.mapAtomsByNameTo(paramsByMol.getValue(mol).mol)
						}

					ff.calcEnergy(staticUnaffectedAtomsByMol, paramsByMol, molToParams)
						.also { staticEnergiesTask.increment() }
				}

			// compile the design positions
			val posInfos = ArrayList<CompiledConfSpace.PosInfo>()
			for (posInfo in confSpaceIndex.positions) {

				// compile the fragments
				val fragInfos = ArrayList<CompiledConfSpace.FragInfo>()
				// TODO: parallelize this
				posInfo.forEachFrag { assignmentInfo, confInfo ->
					fragInfos.add(confInfo.fragInfo.compile(fixedAtoms, assignmentInfo))
				}

				// compile the conformations
				val confInfos = ArrayList<CompiledConfSpace.ConfInfo>()
				// TODO: parallelize this
				posInfo.forEachConf { assignmentInfo, confInfo ->
					confInfos.add(confInfo.compile(infoIndexer, fixedAtoms, assignmentInfo, params))
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
			atomPairs.compileStatic(staticAffectedAtomsByMol, staticUnaffectedAtomsByMol, fixedAtoms, params)
			// TODO: parallelize this
			for (posInfo1 in confSpaceIndex.positions) {

				posInfo1.forEachFrag { assignmentInfo1, confInfo1 ->

					// compile the pos atom pairs
					atomPairs.compilePos(confInfo1, fixedAtoms, assignmentInfo1, params)
					atomPairsTask.increment()

					// compile the pos-static atom pairs
					atomPairs.compilePosStatic(confInfo1, fixedAtoms, assignmentInfo1, params)
					atomPairsTask.increment()
				}

				for (posInfo2 in confSpaceIndex.positions.subList(0, posInfo1.index)) {
					(posInfo1 to posInfo2).forEachFrag { assignmentInfo1, confInfo1, assignmentInfo2, confInfo2 ->

						// compile the pos-pos atom pairs
						atomPairs.compilePosPos(confInfo1, confInfo2, fixedAtoms, assignmentInfo1, assignmentInfo2, params)
						atomPairsTask.increment()
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
					infoIndexer.molInfos,
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

	private class InfoIndexer(val mols: List<Molecule>) {

		// index all the molecules
		val molInfos: List<CompiledConfSpace.MolInfo>
		val molIndices: Map<Molecule,Int>
		init {
			val molInfos = ArrayList<CompiledConfSpace.MolInfo>()
			molIndices = IdentityHashMap()
			for (mol in mols) {
				molIndices[mol] = molInfos.size
				molInfos.add(CompiledConfSpace.MolInfo(mol.name, mol.type))
			}
			this.molInfos = molInfos
		}

		// index all the residues
		val resInfos: List<CompiledConfSpace.ResInfo>
		val resIndices: Map<Polymer.Residue,Int>
		init {
			val resInfos = ArrayList<CompiledConfSpace.ResInfo>()
			resIndices = IdentityHashMap()
			for (mol in mols.filterIsInstance<Polymer>()) {
				for (chain in mol.chains) {
					for ((resi, res) in chain.residues.withIndex()) {
						resIndices[res] = resInfos.size
						resInfos.add(CompiledConfSpace.ResInfo(chain.id, res.id, res.type, resi))
					}
				}
			}
			this.resInfos = resInfos
		}

		fun indexOfMol(mol: Molecule): Int =
			molIndices[mol]
				?: throw IllegalArgumentException("molecule has no index: $mol")

		fun indexOfRes(res: Polymer.Residue?): Int =
			if (res != null) {
				resIndices[res] ?: throw IllegalArgumentException("residue has no index: $res")
			} else {
				-1
			}
	}

	/**
	 * Compiles the motions for this molecule
	 */
	private fun MolMotion.Description.compile(
		mol: Molecule,
		fixedAtoms: FixedAtoms
	): CompiledConfSpace.MotionInfo = when (this) {

		is DihedralAngle.MolDescription -> CompiledConfSpace.MotionInfo.DihedralAngle(
			minDegrees,
			maxDegrees,
			abcd = listOf(a, b, c, d).map { fixedAtoms.getStatic(it).index },
			rotated = rotatedAtoms.map { fixedAtoms.getStatic(it).index }
		)

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
	private fun ConfSpaceIndex.FragInfo.compile(fixedAtoms: FixedAtoms, assignmentInfo: Assignments.AssignmentInfo): CompiledConfSpace.FragInfo {

		// collect the atoms for this fragment (including dynamic fixed atoms), and assign indices
		val confAtoms = AtomIndex(orderAtoms(fixedAtoms[posInfo].dynamics, assignmentInfo))

		return CompiledConfSpace.FragInfo(
			name = frag.id,
			atomNames = confAtoms.map { it.name }
		)
	}

	/**
	 * Compiles the conformation
	 */
	private fun ConfSpaceIndex.ConfInfo.compile(infoIndexer: InfoIndexer, fixedAtoms: FixedAtoms, assignmentInfo: Assignments.AssignmentInfo, params: MolsParams): CompiledConfSpace.ConfInfo {

		// collect the atoms for this conf (including dynamic fixed atoms), and assign indices
		val confAtoms = AtomIndex(fragInfo.orderAtoms(fixedAtoms[posInfo].dynamics, assignmentInfo))

		// compute the internal energies of the conformation atoms
		val internalEnergies = forcefields.map { ff ->
			val fragParams = params[ff, fragInfo]
			val molToFrag = assignmentInfo.mol.mapAtomsByNameTo(fragParams.mol)
			confAtoms
				.mapNotNull { atom -> ff.internalEnergy(params[ff, fragInfo], molToFrag.getBOrThrow(atom)) }
				.sum()
		}

		return CompiledConfSpace.ConfInfo(
			id = id,
			type = fragInfo.frag.type,
			atoms = confAtoms.map { it.compile(infoIndexer, posInfo.pos.mol) },
			fragIndex = fragInfo.index,
			motions = confConfSpace.motions.map { it.compile(confAtoms, fixedAtoms, assignmentInfo) },
			internalEnergies = internalEnergies
		)
	}

	/**
	 * Compiles the continuous motion
	 */
	private fun ConfMotion.Description.compile(
		confAtoms: AtomIndex,
		fixedAtoms: FixedAtoms,
		assignmentInfo: Assignments.AssignmentInfo
	): CompiledConfSpace.MotionInfo = when (this) {

		is DihedralAngle.ConfDescription -> {

			val a = assignmentInfo.confSwitcher.atomResolverOrThrow.resolveOrThrow(motion.a)
			val b = assignmentInfo.confSwitcher.atomResolverOrThrow.resolveOrThrow(motion.b)
			val c = assignmentInfo.confSwitcher.atomResolverOrThrow.resolveOrThrow(motion.c)
			val d = assignmentInfo.confSwitcher.atomResolverOrThrow.resolveOrThrow(motion.d)

			CompiledConfSpace.MotionInfo.DihedralAngle(
				minDegrees,
				maxDegrees,
				abcd = listOf(a, b, c, d)
					.map { confAtoms.getOrStatic(it, fixedAtoms, assignmentInfo) },
				rotated = DihedralAngle.findRotatedAtoms(assignmentInfo.mol, b, c)
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
			infoIndexer.indexOfRes((mol as? Polymer)?.findResidue(this))
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
	private fun List<AtomPairs>.compilePos(
		confInfo: ConfSpaceIndex.ConfInfo,
		fixedAtoms: FixedAtoms,
		assignmentInfo: Assignments.AssignmentInfo,
		params: MolsParams
	) {
		val confAtoms = AtomIndex(confInfo.fragInfo.orderAtoms(fixedAtoms[confInfo.posInfo].dynamics, assignmentInfo))
		val confAtomsByMol = identityHashMapOf(assignmentInfo.mol to confAtoms as List<Atom>)
		val molInfos = forcefields.map { ff ->
			MolInfo(assignmentInfo.mol, params[ff, confInfo.fragInfo])
		}

		ForcefieldParams.forEachPair(confAtomsByMol, confAtomsByMol) { mol1, atom1, mol2, atom2, dist ->

			for ((ffi, ff) in forcefields.withIndex()) {

				// map atoms onto the params mols
				val molInfo = molInfos[ffi]
				val patom1 = molInfo.molToFrag.getBOrThrow(atom1)
				val patom2 = molInfo.molToFrag.getBOrThrow(atom2)

				ff.pairParams(molInfo.params, patom1, molInfo.params, patom2, dist)?.let { params ->
					this[ffi].pos.add(
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
	private fun List<AtomPairs>.compilePosPos(
		confInfo1: ConfSpaceIndex.ConfInfo,
		confInfo2: ConfSpaceIndex.ConfInfo,
		fixedAtoms: FixedAtoms,
		assignmentInfo1: Assignments.AssignmentInfo,
		assignmentInfo2: Assignments.AssignmentInfo,
		params: MolsParams
	) {
		val conf1Atoms = AtomIndex(confInfo1.fragInfo.orderAtoms(fixedAtoms[confInfo1.posInfo].dynamics, assignmentInfo1))
		val conf1AtomsByMol = identityHashMapOf(assignmentInfo1.mol to conf1Atoms as List<Atom>)
		val mol1Infos = forcefields.map { ff ->
			MolInfo(assignmentInfo1.mol, params[ff, confInfo1.fragInfo])
		}

		val conf2Atoms = AtomIndex(confInfo2.fragInfo.orderAtoms(fixedAtoms[confInfo2.posInfo].dynamics, assignmentInfo2))
		val conf2AtomsByMol = identityHashMapOf(assignmentInfo2.mol to conf2Atoms as List<Atom>)
		val mol2Infos = forcefields.map { ff ->
			MolInfo(assignmentInfo2.mol, params[ff, confInfo2.fragInfo])
		}

		ForcefieldParams.forEachPair(conf1AtomsByMol, conf2AtomsByMol) { mol1, atom1, mol2, atom2, dist ->

			for ((ffi, ff) in forcefields.withIndex()) {

				// map atoms onto the params mols
				val mol1Info = mol1Infos[ffi]
				val patom1 = mol1Info.molToFrag.getBOrThrow(atom1)
				val mol2Info = mol2Infos[ffi]
				val patom2 = mol2Info.molToFrag.getBOrThrow(atom2)

				ff.pairParams(mol1Info.params, patom1, mol2Info.params, patom2, dist)?.let { params ->
					this[ffi].posPos.add(
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
	private fun List<AtomPairs>.compilePosStatic(
		confInfo: ConfSpaceIndex.ConfInfo,
		fixedAtoms: FixedAtoms,
		assignmentInfo: Assignments.AssignmentInfo,
		params: MolsParams
	) {
		val confAtoms = AtomIndex(confInfo.fragInfo.orderAtoms(fixedAtoms[confInfo.posInfo].dynamics, assignmentInfo))
		val confAtomsByMol = identityHashMapOf(assignmentInfo.mol to confAtoms as List<Atom>)
		val confMolInfos = forcefields.map { ff ->
			MolInfo(assignmentInfo.mol, params[ff, confInfo.fragInfo])
		}

		val staticAtomsByMol = fixedAtoms.staticAtomsByMol.entries
			.associate { (srcMol, srcAtoms) ->
				// map the atoms onto the assignment molecule, if needed
				val dstMol = assignmentInfo.maps.mols.getB(srcMol)
				if (dstMol != null) {
					dstMol to srcAtoms.map { assignmentInfo.maps.atoms.getBOrThrow(it) }
				} else {
					srcMol to srcAtoms
				}
			}
		val staticMols = staticAtomsByMol.keys.toList()
		val staticMolInfos = forcefields.map { ff ->
			staticMols.associateIdentity { mol ->
				val srcMol = assignmentInfo.maps.mols.getA(mol) ?: mol
				mol to MolInfo(mol, params[ff, srcMol])
			}
		}

		ForcefieldParams.forEachPair(confAtomsByMol, staticAtomsByMol) { cmol, catom, smol, satom, dist ->

			for ((ffi, ff) in forcefields.withIndex()) {

				// map atoms onto the assignment mols, if needed
				val srcSAtom = assignmentInfo.maps.atoms.getA(satom) ?: satom

				// map atoms onto the params mols
				val confMolInfo = confMolInfos[ffi]
				val patom1 = confMolInfo.molToFrag.getBOrThrow(catom)
				val staticMolInfo = staticMolInfos[ffi].getValue(smol)
				val patom2 = staticMolInfo.molToFrag.getBOrThrow(satom)

				ff.pairParams(confMolInfo.params, patom1, staticMolInfo.params, patom2, dist)?.let { params ->
					this[ffi].posStatic.add(
						confInfo.posInfo.index,
						confInfo.fragInfo.index,
						confAtoms.getOrThrow(catom),
						fixedAtoms.getStatic(srcSAtom).index,
						params.list
					)
				}
			}
		}
	}

	/**
	 * Compile atom pairs for static interactions
	 */
	private fun List<AtomPairs>.compileStatic(
		staticAffectedAtomsByMol: Map<Molecule,Set<Atom>>,
		staticUnaffectedAtomsByMol: Map<Molecule,List<Atom>>,
		fixedAtoms: FixedAtoms,
		params: MolsParams
	) {
		// first, do affected internal interactions
		val affectedAtomsByMol = staticAffectedAtomsByMol
			.mapValuesIdentity { (_, atoms) -> atoms.toList().sortedBy { it.name } }
		val affectedMols = affectedAtomsByMol.keys.toList()
		val affectedMolInfos = forcefields.map { ff ->
			affectedMols.associateIdentity { mol ->
				mol to MolInfo(mol, params[ff, mol])
			}
		}

		ForcefieldParams.forEachPair(affectedAtomsByMol, affectedAtomsByMol) { mol1, atom1, mol2, atom2, dist ->

			for ((ffi, ff) in forcefields.withIndex()) {

				// map atoms onto the params mols
				val mol1Info = affectedMolInfos[ffi].getValue(mol1)
				val patom1 = mol1Info.molToFrag.getBOrThrow(atom1)
				val mol2Info = affectedMolInfos[ffi].getValue(mol2)
				val patom2 = mol2Info.molToFrag.getBOrThrow(atom2)

				ff.pairParams(mol1Info.params, patom1, mol2Info.params, patom2, dist)?.let { params ->
					this[ffi].static.add(
						fixedAtoms.getStatic(atom1).index,
						fixedAtoms.getStatic(atom2).index,
						params.list
					)
				}
			}
		}

		// then, add affected-unaffected interactions
		val unaffectedAtomsByMol = staticUnaffectedAtomsByMol
		val unaffectedMols = unaffectedAtomsByMol.keys.toList()
		val unaffectedMolInfos = forcefields.map { ff ->
			unaffectedMols.associateIdentity { mol ->
				mol to MolInfo(mol, params[ff, mol])
			}
		}

		ForcefieldParams.forEachPair(affectedAtomsByMol, unaffectedAtomsByMol) { amol, aatom, umol, uatom, dist ->

			for ((ffi, ff) in forcefields.withIndex()) {

				// map atoms onto the params mols
				val amolInfo = affectedMolInfos[ffi].getValue(amol)
				val patom1 = amolInfo.molToFrag.getBOrThrow(aatom)
				val umolInfo = unaffectedMolInfos[ffi].getValue(umol)
				val patom2 = umolInfo.molToFrag.getBOrThrow(uatom)

				ff.pairParams(amolInfo.params, patom1, umolInfo.params, patom2, dist)?.let { params ->
					this[ffi].static.add(
						fixedAtoms.getStatic(aatom).index,
						fixedAtoms.getStatic(uatom).index,
						params.list
					)
				}
			}
		}
	}
}
