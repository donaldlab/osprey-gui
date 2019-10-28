package edu.duke.cs.ospreygui.features.win

import cuchaz.kludge.imgui.Commands
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.WindowFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.ospreygui.OspreyGui


class AboutOspreyGui : WindowFeature {

	override val id = FeatureId("about.ospreygui")

	var shouldOpen: Boolean = false

	private val popupId = id.toString()

	override fun menu(imgui: Commands, win: WindowCommands) = imgui.run {
		if (menuItem("About ${OspreyGui.name}")) {
			shouldOpen = true
		}
	}

	override fun gui(imgui: Commands, win: WindowCommands) = imgui.run {

		if (shouldOpen) {
			shouldOpen = false
			openPopup(popupId)
		}

		popup(popupId) {
			text(OspreyGui.name)
			text("v${OspreyGui.version}")
			spacing()
			text("Developed by the Donald Lab")
			text("at Duke University")
		}
	}
}