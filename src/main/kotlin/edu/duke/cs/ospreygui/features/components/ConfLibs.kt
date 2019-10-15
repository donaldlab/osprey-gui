package edu.duke.cs.ospreygui.features.components

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.gui.Alert
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.io.read
import edu.duke.cs.ospreygui.prep.ConfSpacePrep
import java.nio.file.Paths


/**
 * A cache for the built-in conflib metadata.
 */
object ConfLibs {

	private val builtInConflibPaths = listOf(
		"conflib/lovell.conflib.toml"
	)

	data class ConfLibInfo(
		val name: String,
		val description: String?,
		val citation: String?
	)

	val infos: List<ConfLibInfo> by lazy {
		ArrayList<ConfLibInfo>().apply {
			for (path in builtInConflibPaths) {
				val conflib = ConfLib.from(OspreyGui.getResourceAsString(path))
				add(ConfLibInfo(
					conflib.name,
					conflib.description,
					conflib.citation
				))
			}
		}
	}
}

class ConfLibPicker(val prep: ConfSpacePrep) {

	private var libDir = Paths.get("").toAbsolutePath()
	private val conflibFilter = FilterList(listOf("conflib.toml"))

	private val alert = Alert()

	var onAdd: ((ConfLib) -> Unit)? = null

	fun render(imgui: Commands) = imgui.run {

		fun conflibTooltip(name: String?, desc: String?, citation: String?) {
			if (isItemHovered()) {
				beginTooltip()
				if (name != null) {
					text(name)
				}
				if (desc != null) {
					text(desc)
				}
				if (citation != null) {
					text(citation)
				}
				endTooltip()
			}
		}

		// show available libraries
		text("Conformation Libraries")
		beginChild("libs", 300f, 100f, true)
		for (conflib in prep.conflibs) {
			text(conflib.name)
			conflibTooltip(conflib.name, conflib.description, conflib.citation)
		}
		endChild()

		if (button("Add")) {
			openPopup("addlib")
		}
		if (beginPopup("addlib")) {
			for (info in ConfLibs.infos) {
				if (menuItem(info.name)) {
					addLib(OspreyGui.getResourceAsString("conflib/lovell.conflib.toml"))
				}
				conflibTooltip(null, info.description, info.citation)
			}
			endPopup()
		}

		sameLine()

		if (button("Add from file")) {
			addLibFromFile()
		}

		alert.render(this)
	}

	private fun addLibFromFile() {
		FileDialog.openFiles(
			conflibFilter,
			defaultPath = libDir
		)?.let { paths ->
			paths.firstOrNull()?.parent?.let { libDir = it }
			for (path in paths) {
				addLib(path.read())
			}
		}
	}

	private fun addLib(toml: String) {
		try {
			val conflib = prep.conflibs.add(toml)
			onAdd?.invoke(conflib)
		} catch (ex: ConfSpacePrep.DuplicateConfLibException) {
			alert.show("Skipped adding duplicate Conformation Library:\n${ex.conflib.name}")
		}
	}
}
