package edu.duke.cs.ospreygui

import cuchaz.kludge.tools.autoCloser
import cuchaz.kludge.tools.expand
import cuchaz.kludge.tools.toFloat
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.Window
import edu.duke.cs.molscope.gui.features.slide.MenuRenderSettings
import edu.duke.cs.molscope.gui.features.slide.CameraTool
import edu.duke.cs.molscope.gui.features.win.AboutMolscope
import edu.duke.cs.molscope.gui.features.win.Exit
import edu.duke.cs.molscope.gui.features.win.MenuColorsMode
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.render.RenderSettings
import edu.duke.cs.molscope.view.BallAndStick
import edu.duke.cs.ospreygui.features.slide.ClashViewer
import edu.duke.cs.ospreygui.features.win.*
import org.joml.AABBf
import org.slf4j.LoggerFactory


fun main() = autoCloser {

	OspreyGui.log.info	("Osprey GUI started!")

	// create the default exception handler
	Thread.setDefaultUncaughtExceptionHandler { _, t ->
		OspreyGui.log.error("Uncaught exception", t)
	}

	// auto-stop the local service, if it gets started
	LocalServiceRunner.autoClose()

	// open a window
	val win = Window(
		width = 1280, // TODO: make this bigger?
		height = 720,
		title = "OSPREY"
	).autoClose()

	// add window features
	win.features.run {
		if (OspreyGui.dev) {
			menu("Dev") {
				add(LocalService())
			}
		}
		menu("File") {
			add(ImportPDB())
			add(OpenOMOL())
			addSeparator()
			add(NewConfSpace())
			add(OpenConfSpace())
			addSeparator()
			add(Exit())
		}
		menu("View") {
			add(MenuColorsMode())
		}
		menu("Help") {
			// TODO: about osprey?
			add(AboutOspreyGui())
			add(AboutMolscope())
		}
	}

	win.waitForClose()

} // end of scope here cleans up all autoClose() resources


val defaultRenderSettings = RenderSettings().apply {
	// these settings looked nice on a small protein
	shadingWeight = 1.4f
	lightWeight = 1.6f
	depthWeight = 1.0f
}

fun Molecule.show(focusAtom: Atom? = null, wait: Boolean = false) {
	val mol = this
	Thread {
		autoCloser {

			// open a window
			val win = Window(
				width = 1280,
				height = 720,
				title = "Molscope"
			).autoClose()

			// add window features
			win.features.run {
				menu("View") {
					add(MenuColorsMode())
				}
				menu("Help") {
					add(AboutMolscope())
				}
			}

			// prepare the slide
			win.slides.add(Slide("molecule").apply {
				lock { s ->

					s.views.add(BallAndStick(mol))

					if (focusAtom == null) {
						s.camera.lookAtEverything()
					} else {
						s.camera.lookAtBox(AABBf().apply {
							setMin(focusAtom.pos.toFloat())
							setMax(focusAtom.pos.toFloat())
							expand(10f)
						})
					}

					s.features.menu("View") {
						add(CameraTool())
						add(MenuRenderSettings(defaultRenderSettings))
						add(ClashViewer())
					}
				}
			})

			win.waitForClose()
		}
	}.apply {

		name = "Molscope"

		// keep the JVM from exiting while the window is open
		isDaemon = false

		start()

		// wait for the window to close if needed
		if (wait) {
			join()
		}
	}
}
