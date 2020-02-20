package edu.duke.cs.ospreygui.features.win

import cuchaz.kludge.imgui.Commands
import edu.duke.cs.molscope.gui.WindowCommands
import edu.duke.cs.molscope.gui.WindowFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.ospreyservice.OspreyService
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

	private var service: OspreyService.Instance? = null

	val isRunning get() = service != null

	fun start() {
		if (service == null) {
			service = OspreyService.Instance(serviceDir, wait = false)
		}
	}

	override fun close() {
		service?.close()
		service = null
	}
}