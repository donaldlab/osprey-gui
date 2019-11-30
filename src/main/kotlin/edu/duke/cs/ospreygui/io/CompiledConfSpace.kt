package edu.duke.cs.ospreygui.io

import cuchaz.kludge.tools.x
import cuchaz.kludge.tools.y
import cuchaz.kludge.tools.z
import edu.duke.cs.ospreygui.compiler.CompiledConfSpace
import org.joml.Vector3dc


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
		for ((confi1, conf1) in pos1.confs.withIndex()) {

			// write the pos atom pairs
			write("\n")
			write("[pos.$posi1.conf.$confi1.atomPairs.single] # %s=%s\n",
				pos1.name, conf1.id
			)
			for ((ffi, atomPairs) in atomPairs.withIndex()) {
				write("$ffi = [\n")
				for (atomPair in atomPairs.singles[posi1, confi1]) {
					write("\t[ %2d, %2d, %6d ], # %s - %s\n",
						atomPair.atomi1,
						atomPair.atomi2,
						atomPair.paramsi,
						conf1.atoms[atomPair.atomi1].name,
						conf1.atoms[atomPair.atomi2].name
					)
				}
				write("]\n")
			}

			// write the pos-static atom pairs
			write("\n")
			write("[pos.$posi1.conf.$confi1.atomPairs.static] # %s=%s\n",
				pos1.name, conf1.id
			)
			for ((ffi, atomPairs) in atomPairs.withIndex()) {
				write("$ffi = [\n")
				for (atomPair in atomPairs.statics[posi1, confi1]) {
					write("\t[ %2d, %2d, %6d ], # %s - %s\n",
						atomPair.atomi1,
						atomPair.atomi2,
						atomPair.paramsi,
						conf1.atoms[atomPair.atomi1].name,
						staticAtoms[atomPair.atomi2].name
					)
				}
				write("]\n")
			}

			for (posi2 in 0 until posi1) {
				val pos2 = positions[posi2]
				for ((confi2, conf2) in pos2.confs.withIndex()) {

					// write the pos-pos atom pairs
					write("\n")
					write("[pos.$posi1.conf.$confi1.atomPairs.pos.$posi2.conf.$confi2] # %s=%s, %s=%s\n",
						pos1.name, conf1.id,
						pos2.name, conf2.id
					)
					for ((ffi, atomPairs) in atomPairs.withIndex()) {
						write("$ffi = [\n")
						for (atomPair in atomPairs.pairs[posi1, confi1, posi2, confi2]) {
							write("\t[ %2d, %2d, %6d ], # %s - %s\n",
								atomPair.atomi1,
								atomPair.atomi2,
								atomPair.paramsi,
								conf1.atoms[atomPair.atomi1].name,
								conf2.atoms[atomPair.atomi2].name
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

	// TEMP
	buf.toString().lines().forEachIndexed { i, line -> println("$i: $line") }

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
