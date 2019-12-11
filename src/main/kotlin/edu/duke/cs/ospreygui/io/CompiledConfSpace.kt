package edu.duke.cs.ospreygui.io

import cuchaz.kludge.tools.x
import cuchaz.kludge.tools.y
import cuchaz.kludge.tools.z
import edu.duke.cs.ospreygui.compiler.AtomPairs
import edu.duke.cs.ospreygui.compiler.CompiledConfSpace
import org.joml.Vector3dc
import java.io.ByteArrayOutputStream
import java.io.DataOutput
import java.io.DataOutputStream


/**
 * Writes the compiled conformation space in a human-readable TOML format.
 *
 * NOTE: for all but small conformation spaces, the resulting TOML file
 * will be too large to read in Java. The JVM runs out of heap memory on default settings!
 */
fun CompiledConfSpace.toToml(): String {

	val buf = StringBuilder()
	fun write(str: String, vararg args: Any) = buf.append(String.format(str, *args))

	// write out conf space properties
	write("\n")
	write("name = %s\n", name.doubleQuote())
	write("forcefields = [\n")
	for (forcefield in forcefields) {
		write("\t[ %s, %s ],\n",
			forcefield.name.quote(),
			forcefield.ospreyImplementation.quote()
		)
	}
	write("]\n")

	// write out forcefield settings
	for ((ffi, ff) in forcefields.withIndex()) {
		ff.settings
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

	// write out the static atoms in order
	write("\n")
	write("[static]\n")
	write("atoms = [\n")
	for ((atomi, atom) in staticAtoms.withIndex()) {
		write("\t{ xyz = %s, name = %20s }, # %d\n",
			atom.pos.toToml(),
			atom.name.doubleQuote(),
			atomi
		)
	}
	write("]\n")

	// write out the internal energies for the static atoms
	write("energy = [\n")
	for (energy in staticEnergies) {
		write("\t%f,\n",
			energy
		)
	}
	write("]\n")

	for ((posi, pos) in positions.withIndex()) {

		// write out the design position
		write("\n")
		write("[pos.$posi]\n")
		write("name = %s\n", pos.name.doubleQuote())

		for ((confi, conf) in pos.confs.withIndex()) {

			// write out the conformation
			write("\n")
			write("[pos.$posi.conf.$confi] # %s:%s\n",
				pos.name, conf.id
			)
			write("id = %s\n", conf.id.quote())
			write("type = %s\n", conf.type.quote())
			write("fragIndex = %d\n", conf.fragIndex)

			// write out the atoms for this conformation
			write("atoms = [\n")
			for ((atomi, atom) in conf.atoms.withIndex()) {
				write("\t{ xyz = %s, name = %8s }, # %d\n",
					atom.pos.toToml(),
					atom.name.doubleQuote(),
					atomi
				)
			}
			write("]\n")

			// write the continuous motions, if needed
			if (conf.motions.isNotEmpty()) {

				write("motions = [\n")
				for (motion in conf.motions) {
					when (motion) {

						is CompiledConfSpace.MotionInfo.DihedralAngle -> {
							write("\t{ type = %s, bounds = [ %f, %f ], abcd = [ %s ], rotated = [ %s ] },\n",
								"dihedral".quote(),
								motion.minDegrees,
								motion.maxDegrees,
								motion.abcd.joinToString(", "),
								motion.rotated.joinToString(", ")
							)
						}
					}
				}
				write("]\n")
			}

			// write the internal energies
			write("energy = [\n")
			for (ffi in forcefields.indices) {
				write("\t%f,\n",
					conf.internalEnergies[ffi]
				)
			}
			write("]\n")
		}
	}

	for ((posi1, pos1) in positions.withIndex()) {
		for ((fragi1, frag1) in pos1.fragments.withIndex()) {

			// write the pos atom pairs
			write("\n")
			write("[pos.$posi1.frag.$fragi1.atomPairs.single] # %s=%s\n",
				pos1.name, frag1.name
			)
			for ((ffi, atomPairs) in atomPairs.withIndex()) {
				write("$ffi = [\n")
				for (atomPair in atomPairs.singles[posi1, fragi1]) {
					write("\t[ %2d, %2d, %6d ], # %s - %s\n",
						atomPair.atomi1,
						atomPair.atomi2,
						atomPair.paramsi,
						frag1.atomNames[atomPair.atomi1],
						frag1.atomNames[atomPair.atomi2]
					)
				}
				write("]\n")
			}

			// write the pos-static atom pairs
			write("\n")
			write("[pos.$posi1.frag.$fragi1.atomPairs.static] # %s=%s\n",
				pos1.name, frag1.name
			)
			for ((ffi, atomPairs) in atomPairs.withIndex()) {
				write("$ffi = [\n")
				for (atomPair in atomPairs.statics[posi1, fragi1]) {
					write("\t[ %2d, %2d, %6d ], # %s - %s\n",
						atomPair.atomi1,
						atomPair.atomi2,
						atomPair.paramsi,
						frag1.atomNames[atomPair.atomi1],
						staticAtoms[atomPair.atomi2].name
					)
				}
				write("]\n")
			}

			for (posi2 in 0 until posi1) {
				val pos2 = positions[posi2]
				for ((fragi2, frag2) in pos2.fragments.withIndex()) {

					// write the pos-pos atom pairs
					write("\n")
					write("[pos.$posi1.frag.$fragi1.atomPairs.pos.$posi2.frag.$fragi2] # %s=%s, %s=%s\n",
						pos1.name, frag1.name,
						pos2.name, frag2.name
					)
					for ((ffi, atomPairs) in atomPairs.withIndex()) {
						write("$ffi = [\n")
						for (atomPair in atomPairs.pairs[posi1, fragi1, posi2, fragi2]) {
							write("\t[ %2d, %2d, %6d ], # %s - %s\n",
								atomPair.atomi1,
								atomPair.atomi2,
								atomPair.paramsi,
								frag1.atomNames[atomPair.atomi1],
								frag2.atomNames[atomPair.atomi2]
							)
						}
						write("]\n")
					}
				}
			}
		}
	}

	// write the cache params
	write("\n")
	write("[ffparams]\n")
	for ((ffi, atomPairs) in atomPairs.withIndex()) {
		write("%d = [\n", ffi)
		for ((paramsi, params) in atomPairs.paramsCache.withIndex()) {
			write("\t[ %s ], # %d\n",
				params.joinToString(", ") { it.toString() },
				paramsi
			)
		}
		write("]\n")
	}

	return buf.toString()
}


private fun String.quote() = "'$this'"

/**
 * Useful for strings that sometimes contain single quotes,
 * like atom names (eg prime)
 */
private fun String.doubleQuote() = "\"$this\""

private fun Vector3dc.toToml() =
	"[ %12.6f, %12.6f, %12.6f ]".format(x, y, z)


/**
 * Compiled conf spaces are actually big enough that parsing the TOML
 * file can run the JVM out of heap space! So, we need a more efficient format...
 *
 * This is an attempt at encoding a compiled conf space with
 * an efficient binary format.
 *
 * NOTE: This does not produce human-readable output!
 */
fun CompiledConfSpace.toBytes(): ByteArray {

	val buf = ByteArrayOutputStream()
	val out = DataOutputStream(buf)

	// start with an 8-byte magic number
	// _ospccs_ = [Osp]rey [C]ompiled [C]onformation [S]pace
	out.writeBytes("_ospccs_")

	// then write a version number
	out.writeInt(1)

	// write out conf space properties
	out.writeUTF(name)
	out.writeInt(forcefields.size)
	for (forcefield in forcefields) {
		out.writeUTF(forcefield.name)
		out.writeUTF(forcefield.ospreyImplementation)
	}

	// write out forcefield settings
	for (ff in forcefields) {
		for ((key, value) in ff.settings) {
			when (value) {
				is String -> out.writeUTF(value)
				is Int -> out.writeInt(value)
				is Double -> out.writeDouble(value)
				is Boolean -> out.writeBoolean(value)
				else -> throw Error("don't know how to serialize forcefield setting: $key, which is a ${value::class}")
			}
		}
	}

	// write out the static atoms in order
	out.writeInt(staticAtoms.size)
	for (atom in staticAtoms) {
		out.write(atom.pos)
		out.writeUTF(atom.name)
	}

	// write out the internal energies for the static atoms
	for (energy in staticEnergies) {
		out.writeDouble(energy)
	}

	// write out the design positions
	out.writeInt(positions.size)
	for (pos in positions) {

		// write out the design position properties
		out.writeUTF(pos.name)

		// write out the fragments
		out.writeInt(pos.fragments.size)

		// write out the conformations
		out.writeInt(pos.confs.size)
		for (conf in pos.confs) {

			// write out the conformation properties
			out.writeUTF(conf.id)
			out.writeUTF(conf.type)
			out.writeInt(conf.fragIndex)

			// write out the atoms for this conformation
			out.writeInt(conf.atoms.size)
			for (atom in conf.atoms) {
				out.write(atom.pos)
				out.writeUTF(atom.name)
			}

			// write the continuous motions, if needed
			out.writeInt(conf.motions.size)
			for (motion in conf.motions) {
				when (motion) {

					is CompiledConfSpace.MotionInfo.DihedralAngle -> {
						out.writeUTF("dihedral")
						out.writeDouble(motion.minDegrees)
						out.writeDouble(motion.maxDegrees)
						out.writeInt(motion.abcd[0])
						out.writeInt(motion.abcd[1])
						out.writeInt(motion.abcd[2])
						out.writeInt(motion.abcd[3])
						out.writeInt(motion.rotated.size)
						for (atomi in motion.rotated) {
							out.writeInt(atomi)
						}
					}
				}
			}

			// write the internal energies
			for (ffi in forcefields.indices) {
				out.writeDouble(conf.internalEnergies[ffi])
			}
		}
	}

	// write out atom pairs
	for ((posi1, pos1) in positions.withIndex()) {
		for (fragi1 in pos1.fragments.indices) {

			for (ffi in atomPairs.indices) {

				// write the pos atom pairs
				val singles = atomPairs[ffi].singles[posi1, fragi1]
				out.writeInt(singles.size)
				for (atomPair in singles) {
					out.write(atomPair)
				}

				// write the pos-static atom pairs
				val statics = atomPairs[ffi].statics[posi1, fragi1]
				out.writeInt(statics.size)
				for (atomPair in statics) {
					out.write(atomPair)
				}
			}
		}
	}

	// write out more atom pairs
	for ((posi1, pos1) in positions.withIndex()) {
		for (posi2 in 0 until posi1) {
			val pos2 = positions[posi2]

			for (fragi1 in pos1.fragments.indices) {
				for (fragi2 in pos2.fragments.indices) {

					// write the pos-pos atom pairs
					for (ffi in atomPairs.indices) {
						val pairs = atomPairs[ffi].pairs[posi1, fragi1, posi2, fragi2]
						out.writeInt(pairs.size)
						for (atomPair in pairs) {
							out.write(atomPair)
						}
					}
				}
			}
		}
	}

	// write the cached forcefield params
	for (atomPairs in atomPairs) {
		out.writeInt(atomPairs.paramsCache.size)
		for (params in atomPairs.paramsCache) {
			out.writeInt(params.size)
			for (param in params) {
				out.writeDouble(param)
			}
		}
	}

	// write the magic bytes again at the end, for error checking
	out.writeBytes("_ospccs_")

	return buf.toByteArray()
}

private fun DataOutput.write(v: Vector3dc) {
	writeDouble(v.x)
	writeDouble(v.y)
	writeDouble(v.z)
}

private fun DataOutput.write(atomPair: AtomPairs.AtomPair) {
	writeInt(atomPair.atomi1)
	writeInt(atomPair.atomi2)
	writeInt(atomPair.paramsi)
}
