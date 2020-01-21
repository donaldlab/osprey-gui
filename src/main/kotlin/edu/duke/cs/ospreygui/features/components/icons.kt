package edu.duke.cs.ospreygui.features.components

import cuchaz.kludge.imgui.Commands
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.render.LoadedImage
import edu.duke.cs.ospreygui.OspreyGui


enum class Icon(val id: String) {

	SignCheck("sign-check"),
	SignInfo("sign-info"),
	SignWarning("sign-warning"),
	SignError("sign-error");

	val resourcePath get() = "icons/$id.png"
}

object Icons {

	private val loaded = HashMap<Icon,LoadedImage>()

	fun get(win: WindowCommands, icon: Icon) =
		loaded.getOrPut(icon) {
			win.loadImage(OspreyGui.getResourceAsBytes(icon.resourcePath))
		}
}

fun Commands.icon(win: WindowCommands, icon: Icon) {
	image(Icons.get(win, icon).descriptor)
}
