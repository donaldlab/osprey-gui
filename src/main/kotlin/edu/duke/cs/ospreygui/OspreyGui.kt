package edu.duke.cs.ospreygui

import edu.duke.cs.molscope.Molscope
import java.util.*


object OspreyGui {

	const val name = "OSPREY GUI"

	private val properties =
		Properties().apply {
			Molscope.javaClass.getResourceAsStream("build.properties")
				?.use { load(it) }
				?: throw Error("can't find build.properties")
		}

	private fun string(name: String) = properties.getProperty(name) ?: throw NoSuchElementException("no property named $name")
	private fun bool(name: String) = string(name).toBoolean()

	val version = string("version")
	val dev = bool("dev")
}
