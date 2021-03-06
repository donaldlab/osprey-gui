package edu.duke.cs.ospreygui.features.win

import cuchaz.kludge.imgui.Commands
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.WindowFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.forcefield.amber.OperatingSystem
import edu.duke.cs.ospreygui.io.UserSettings
import edu.duke.cs.ospreyservice.OspreyService as Server
import java.nio.file.Paths


class LocalService : WindowFeature {

	override val id = FeatureId("service.local")

	override fun menu(imgui: Commands, win: WindowCommands) = imgui.run {

		// add a checkbox to toggle the local service
		checkbox("Local Service", LocalServiceRunner.isRunning)?.let { isChecked ->
			if (isChecked) {
				LocalServiceRunner.start()
			} else {
				LocalServiceRunner.close()
			}
		}

		Unit
	}
}


object LocalServiceRunner : AutoCloseable {

	private val serviceDir = Paths.get("../osprey-service")

	private var service: Server.Instance? = null

	val isRunning get() = service != null

	init {
		// if we're a linux developer, start a local service by default
		if (OspreyGui.dev && OperatingSystem.get() == OperatingSystem.Linux) {
			start()
		}
	}

	fun start() {
		if (service == null) {
			service = Server.Instance(serviceDir, wait = false)
			UserSettings.serviceProvider = UserSettings.ServiceProvider("localhost")
		}
	}

	override fun close() {
		service?.close()
		service = null
	}
}