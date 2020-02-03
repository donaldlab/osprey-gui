package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.ospreygui.forcefield.amber.findTypeOrThrow
import edu.duke.cs.ospreygui.motions.DihedralAngle
import edu.duke.cs.ospreygui.motions.TranslationRotation
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.prep.DesignPosition
import edu.duke.cs.ospreygui.tools.UnsupportedClassException
import org.tomlj.Toml
import org.tomlj.TomlPosition


fun ConfSpace.toToml(): String {

	val buf = StringBuilder()
	fun write(str: String, vararg args: Any) = buf.append(String.format(str, *args))

	// get the molecules in a stable order
	val mols = mols.map { (_, mol) -> mol }

	// write out the molecules, and keep the atom indices
	val (molsToml, indicesByAtom) = mols.toOMOLMapped(flattenAtomIndices = true)
	write(molsToml)

	fun Atom.index() =
		indicesByAtom[this]
			?: throw NoSuchElementException("no index for atom $this")

	// write down all the fragments
	val wtFrags = wildTypeFragments()
	write("\n")
	val frags = libraryFragments() + wtFrags
	val (fragsToml, idsByFrag) = frags.toToml(resolveIdCollisions = true)
	write(fragsToml)

	fun ConfLib.Fragment.resolvedId() =
		idsByFrag.getValue(this)

	// write the conf space
	write("\n")
	write("[confspace]\n")
	write("name = %s\n", name.quote())

	val posIndices = HashMap<DesignPosition,Int>()
	for ((moli, mol) in mols.withIndex()) {

		// write the design positions, if any
		designPositionsByMol[mol]?.forEach pos@{ pos ->

			// get the position conf space, or skip this pos
			val posConfSpace = positionConfSpaces[pos] ?: return@pos

			val posi = posIndices.size
			posIndices[pos] = posi

			write("\n")
			write("[confspace.positions.$posi]\n")
			write("mol = %s\n", moli)
			write("name = %s\n", pos.name.quote())
			write("type = %s\n", pos.type.quote())

			// write the atoms
			write("atoms = [ %s ]\n",
				pos.currentAtoms
					.map { it.index() }
					.joinToString(", ")
			)

			// write the anchors
			for ((i, group) in pos.anchorGroups.withIndex()) {
				write("\n")
				write("[confspace.positions.$posi.confspace.anchorGroups.$i]\n")
				write("anchors = [\n")
				for (anchor in group) {
					val type = when (anchor) {
						is DesignPosition.Anchor.Single -> "single"
						is DesignPosition.Anchor.Double -> "double"
					}
					write("\t{ type = %s, atoms = [ %s ] },\n",
						type.quote(),
						anchor.anchorAtoms
							.map { it.index() }
							.joinToString(", ")
					)
				}
				write("]\n")
			}

			// write the position conf space
			write("\n")
			write("[confspace.positions.$posi.confspace]\n")
			posConfSpace.wildTypeFragment?.let {
				write("wtfrag = %s\n", it.resolvedId().quote())
			}
			write("mutations = [ %s ]\n",
				posConfSpace.mutations.joinToString(", ") { it.quote() }
			)

			// write the conf conf spaces (in a sorted order)
			for ((confi, space) in posConfSpace.confs.withIndex()) {

				write("[confspace.positions.$posi.confspace.confs.$confi]\n")
				write("frag = %s\n", space.frag.id.quote())
				write("conf = %s\n", space.conf.id.quote())

				// write the motions, if any
				if (space.motions.isNotEmpty()) {
					write("motions = [\n")
					for (motion in space.motions) {
						when (motion) {

							is DihedralAngle.ConfDescription -> {
								write("\t{ type = %s, index = %d, minDegrees = %12.6f, maxDegrees = %12.6f },\n",
									"dihedralAngle".quote(),
									space.frag.motions
										.indexOf(motion.motion)
										.takeIf { it >= 0 }
										?: throw NoSuchElementException("dihedral angle motion is not in fragment"),
									motion.minDegrees,
									motion.maxDegrees
								)
							}

							else -> throw UnsupportedClassException("don't know how to save conformation motion", motion)
						}
					}
					write("]\n")
				}
			}
		}

		// write the molecule motions, if any
		molMotions[mol]?.forEachIndexed { motioni, motion ->
			write("\n")
			write("[confspace.molMotions.$moli.$motioni]\n")
			when (motion) {

				is TranslationRotation.MolDescription -> {
					write("type = %s\n", "translationRotation".quote())
					write("maxTranslationDist = %12.6f\n", motion.maxTranslationDist)
					write("maxRotationDegrees = %12.6f\n", motion.maxRotationDegrees)
				}

				else -> throw UnsupportedClassException("don't know how to save molecule motion", motion)
			}
		}
	}

	return buf.toString()
}

private fun String.quote() = "'$this'"

fun ConfSpace.Companion.fromToml(toml: String): ConfSpace =
	fromTomlWithConfLib(toml).first

/**
 * Returns the conformation space, and the library conformations (ie non-wild-type fragments) as a ConfLib
 */
fun ConfSpace.Companion.fromTomlWithConfLib(toml: String): Pair<ConfSpace,ConfLib> {

	// parse the TOML
	val doc = Toml.parse(toml)
	if (doc.hasErrors()) {
		throw TomlParseException("TOML parsing failure:\n${doc.errors().joinToString("\n")}")
	}

	// read the molecules
	val molsAndAtoms = Molecule.fromOMOLWithAtoms(doc)
	fun getMol(i: Int, pos: TomlPosition?) =
		molsAndAtoms.getOrNull(i)
			?.let { (mol, _) -> mol }
			?: throw TomlParseException("no molecule found with index $i", pos)

	// calculate the types for the molecules
	val typesAndMols =
		molsAndAtoms
			.map { (mol, _) -> mol.findTypeOrThrow() to mol }

	// build the atom lookup
	val atomIndices = HashMap<Int,Atom>().apply {
		for ((_, atomIndices) in molsAndAtoms) {
			for ((i, atom) in atomIndices) {
				put(i, atom)
			}
		}
	}
	fun getAtom(i: Int, pos: TomlPosition?) =
		atomIndices[i]
			?: throw TomlParseException("no atom with index $i", pos)

	// read the fragments, if any
	val frags =
		if (doc.contains("frag")) {
			ConfLib.fragmentsFrom(doc)
		} else {
			HashMap()
		}
	fun getFrag(id: String, pos: TomlPosition?) =
		frags[id] ?: throw TomlParseException("no fragment with id $id", pos)
	
	// read the conf space
	val confSpaceTable = doc.getTableOrThrow("confspace")

	val confSpace = ConfSpace(typesAndMols).apply {

		name = confSpaceTable.getStringOrThrow("name")

		// read the positions, if any
		val positionsTable = confSpaceTable.getTable("positions")
		if (positionsTable != null) {
			for (poskey in positionsTable.keySet()) {
				val posTable = positionsTable.getTableOrThrow(poskey)
				val posPos = positionsTable.inputPositionOf(poskey)

				val moli = posTable.getIntOrThrow("mol")
				val mol = getMol(moli, posPos)

				designPositionsByMol.getOrPut(mol) { ArrayList() }.add(DesignPosition(
					name = posTable.getStringOrThrow("name", posPos),
					type = posTable.getStringOrThrow("type", posPos),
					mol = mol
				).apply pos@{

					// read atoms
					val atomsArray = posTable.getArrayOrThrow("atoms", posPos)
					for (i in 0 until atomsArray.size()) {
						currentAtoms.add(getAtom(atomsArray.getInt(i), posPos))
					}

					// read the pos conf space
					val posConfSpaceTable = posTable.getTableOrThrow("confspace", posPos)
					val posConfSpacePos = posTable.inputPositionOf("confspace")

					positionConfSpaces.getOrMake(this@pos).apply {

						// get the wild type fragment, if any
						wildTypeFragment = posConfSpaceTable.getString("wtfrag")?.let { getFrag(it, posConfSpacePos) }

						// read the mutations
						val mutationsArray = posConfSpaceTable.getArrayOrThrow("mutations", posConfSpacePos)
						for (i in 0 until mutationsArray.size()) {
							mutations.add(mutationsArray.getString(i))
						}

						// read the confs
						val confsTable = posConfSpaceTable.getTable("confs")
						if (confsTable != null) {
							for (confkey in confsTable.keySet()) {
								val confTable = confsTable.getTableOrThrow(confkey)
								val confPos = confsTable.inputPositionOf(confkey)

								val frag = getFrag(confTable.getStringOrThrow("frag"), confPos)

								val confId = confTable.getStringOrThrow("conf")
								val conf = frag.confs[confId]
									?: throw TomlParseException("no conf with id $confId in fragment ${frag.id}", confPos)

								// make the conf conf space
								confs.add(frag, conf).apply {

									// read the motions, if any
									val motionsArray = confTable.getArray("motions")
									if (motionsArray != null) {
										for (motioni in 0 until motionsArray.size()) {
											val motionTable = motionsArray.getTable(motioni)
											val motionPos = motionsArray.inputPositionOf(motioni)

											when (motionTable.getStringOrThrow("type", motionPos)) {

												"dihedralAngle" -> {
													val index = motionTable.getIntOrThrow("index", motionPos)
													motions.add(DihedralAngle.ConfDescription(
														this@pos,
														(frag.motions.getOrNull(index) as? ConfLib.ContinuousMotion.DihedralAngle)
															?: throw NoSuchElementException("no dihedral motion at fragment $frag at index $index"),
														motionTable.getDoubleOrThrow("minDegrees"),
														motionTable.getDoubleOrThrow("maxDegrees")
													))
												}
											}
										}
									}
								}
							}
						}
					}

					// read anchor groups
					val anchorGroupsTable = posConfSpaceTable.getTableOrThrow("anchorGroups", posConfSpacePos)
					for (anchorGroupKey in anchorGroupsTable.keySet()) {
						val anchorGroupTable = anchorGroupsTable.getTableOrThrow(anchorGroupKey)
						val anchorGroupPos = anchorGroupsTable.inputPositionOf(anchorGroupKey)
						val anchorArray = anchorGroupTable.getArrayOrThrow("anchors", anchorGroupPos)

						anchorGroups.add(ArrayList<DesignPosition.Anchor>().apply {

							for (i in 0 until anchorArray.size()) {
								val anchorTable = anchorArray.getTable(i)
								val anchorPos = anchorArray.inputPositionOf(i)

								val anchorAtomsArray = anchorTable.getArrayOrThrow("atoms", anchorPos)

								when (anchorTable.getStringOrThrow("type", anchorPos)) {
									"single" ->
										add(anchorSingle(
											a = getAtom(anchorAtomsArray.getInt(0), anchorPos),
											b = getAtom(anchorAtomsArray.getInt(1), anchorPos),
											c = getAtom(anchorAtomsArray.getInt(2), anchorPos)
										))
									"double" ->
										add(anchorDouble(
											a = getAtom(anchorAtomsArray.getInt(0), anchorPos),
											b = getAtom(anchorAtomsArray.getInt(1), anchorPos),
											c = getAtom(anchorAtomsArray.getInt(2), anchorPos),
											d = getAtom(anchorAtomsArray.getInt(3), anchorPos)
										))
								}
							}
						})
					}
				})
			}
		}

		// read the molecule motions, if any
		val molMotionsTable = confSpaceTable.getTable("molMotions")
		if (molMotionsTable != null) {

			for (molkey in molMotionsTable.keySet()) {
				val moli = molkey.toInt()
				val (_, mol) = mols[moli]

				val motionsTable = molMotionsTable.getTable(molkey)
				if (motionsTable != null) {

					for (motionkey in motionsTable.keySet()) {
						val motionTable = motionsTable.getTableOrThrow(motionkey)

						when (motionTable.getString("type")) {

							"translationRotation" -> {
								molMotions.getOrPut(mol) { ArrayList() }.add(TranslationRotation.MolDescription(
									mol,
									motionTable.getDoubleOrThrow("maxTranslationDist"),
									motionTable.getDoubleOrThrow("maxRotationDegrees")
								))
							}
						}
					}
				}
			}
		}
	}

	val wtFragIds = confSpace.wildTypeFragments()
		.map { it.id }
		.toSet()

	val confLib = ConfLib(
		name = "Conformation Space",
		fragments = frags.filter { (id, _) -> id !in wtFragIds },
		description = "from the conformation space named: \"${confSpace.name}\""
	)

	return confSpace to confLib
}
