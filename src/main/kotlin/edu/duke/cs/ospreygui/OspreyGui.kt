package edu.duke.cs.ospreygui

import java.nio.charset.Charset
import java.util.*


object OspreyGui {

	const val name = "OSPREY GUI"

	private val properties =
		Properties().apply {
			getResourceAsStream("build.properties")
				?.use { load(it) }
				?: throw Error("can't find build.properties")
		}

	private fun string(name: String) = properties.getProperty(name) ?: throw NoSuchElementException("no property named $name")
	private fun bool(name: String) = string(name).toBoolean()

	val version = string("version")
	val dev = bool("dev")

	fun getResourceAsStream(path: String) = OspreyGui.javaClass.getResourceAsStream(path)

	fun getResourceAsString(path: String, charset: Charset = Charsets.UTF_8) =
		getResourceAsStream(path).use { stream -> stream.reader(charset).readText() }

	fun getResourceAsBytes(path: String) =
		getResourceAsStream(path).use { stream -> stream.readBytes() }
}
