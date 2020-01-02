package edu.duke.cs.ospreygui.io

import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.tools.identityHashSet
import edu.duke.cs.ospreygui.forcefield.amber.partitionAndAtomMap
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.prep.DesignPosition
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
	val frags = fragments()
	write("\n")
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
		val positions = designPositionsByMol[mol] ?: continue
		for (pos in positions) {

			// get the position conf space, or skip this pos
			val posConfSpace = positionConfSpaces[pos] ?: continue

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
			write("confs = [\n")
			for ((frag, confs) in posConfSpace.confs) {

				// don't write empty conf list, the TOML file won't be parseable
				// (might be a bug in the TOML parser? I think empty arrays [] are supposed to parse ...)
				if (confs.isEmpty()) {
					continue
				}

				write("\t{ frag = %12s, confs = [ %s ] },\n",
					frag.id.quote(),
					confs.joinToString(", ") { it.id.quote() }
				)
			}
			write("]\n")

			// write the motion settings
			for ((frag, motionSettings) in posConfSpace.motionSettings) {
				write("\n")
				write("[confspace.positions.$posi.confspace.motionSettings.${frag.id}]\n")
				write("includeHGroupRotations = %s\n", motionSettings.includeHGroupRotations)
				write("dihedralRadiusDegrees = %s\n", motionSettings.dihedralRadiusDegrees)
			}
		}
	}

	return buf.toString()
}

private fun String.quote() = "'$this'"

fun ConfSpace.Companion.fromToml(toml: String): ConfSpace {

	// parse the TOML
	val doc = Toml.parse(toml)
	if (doc.hasErrors()) {
		throw TomlParseException("TOML parsing failure:\n${doc.errors().joinToString("\n")}")
	}

	// read the molecules
	val molsAndAtoms = Molecule.fromOMOLWithAtoms(doc)
	val (mols, partitionAtomMap) = molsAndAtoms
		.map { (mol, _) -> mol }
		.partitionAndAtomMap(combineSolvent = true)
	fun getMol(i: Int, pos: TomlPosition?) =
		mols.getOrNull(i)
			?.let { (_, mol) -> mol }
			?: throw TomlParseException("no molecule found with index $i", pos)

	// build the atom lookup
	val atomIndices = HashMap<Int,Atom>().apply {
		for ((_, atomIndices) in molsAndAtoms) {
			for ((i, atomA) in atomIndices) {
				val atomB = partitionAtomMap.getBOrThrow(atomA)
				put(i, atomB)
			}
		}
	}
	fun getAtom(i: Int, pos: TomlPosition?) =
		atomIndices[i]
			?: throw TomlParseException("no atom with index $i", pos)

	// read the fragments
	val frags = ConfLib.fragmentsFrom(doc)
	fun getFrag(id: String, pos: TomlPosition?) =
		frags[id] ?: throw TomlParseException("no fragment with id $id", pos)
	
	// read the conf space
	val confSpaceTable = doc.getTableOrThrow("confspace")

	return ConfSpace(mols).apply {

		name = confSpaceTable.getStringOrThrow("name")

		// read the positions
		val positionsTable = confSpaceTable.getTableOrThrow("positions")
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
					val confsArray = posConfSpaceTable.getArrayOrThrow("confs", posConfSpacePos)
					for (i in 0 until confsArray.size()) {
						val confTable = confsArray.getTable(i)
						val confPos = confsArray.inputPositionOf(i)

						val frag = getFrag(confTable.getStringOrThrow("frag"), confPos)
						confs[frag] = identityHashSet<ConfLib.Conf>().apply {
							val table = confTable.getArrayOrThrow("confs", confPos)
							for (j in 0 until table.size()) {
								val confId = table.getString(j)
								val pos = table.inputPositionOf(j)

								val conf = frag.confs[confId]
									?: throw TomlParseException("no conf with id $confId in fragment ${frag.id}", pos)
								add(conf)
							}
						}
					}

					// read the motion settings
					posConfSpaceTable.getTable("motionSettings")?.let { motionSettingsTable ->
						for (fragId in motionSettingsTable.keySet()) {
							val table = motionSettingsTable.getTableOrThrow(fragId)
							val pos = motionSettingsTable.inputPositionOf(fragId)

							val frag = getFrag(fragId, pos)
							motionSettings[frag] = ConfSpace.PositionConfSpace.MotionSettings(
								includeHGroupRotations = table.getBooleanOrThrow("includeHGroupRotations", pos),
								dihedralRadiusDegrees = table.getDoubleOrThrow("dihedralRadiusDegrees", pos)
							)
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
}
