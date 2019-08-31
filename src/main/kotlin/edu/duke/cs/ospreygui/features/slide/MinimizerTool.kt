package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import cuchaz.kludge.tools.toFloat
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.forcefield.amber.*
import org.joml.Vector3d
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


class MinimizerTool : SlideFeature {

	override val id = FeatureId("edit.minimize")

	private val winState = WindowState()

	private class MolInfo(val mol: Molecule) {
		val minInfo = MinimizerInfo(mol)
		val pSelected = Ref.of(true)
	}

	private val molInfos = IdentityHashMap<Molecule,MolInfo>()
	private val pNumSteps = Ref.of(100)
	private var job: Job? = null

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Minimize")) {
			winState.isOpen = true
		}
	}

	private fun Slide.Locked.molViews() = views.mapNotNull { it as? MoleculeRenderView }

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		val molViews = slide.molViews()

		winState.render(
			onOpen = {
				// clear any old mol infos
				molInfos.clear()
			},
			whenOpen = {

				// draw the window
				begin("Minimize##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize))

				molViews
					.map { it.mol }
					.forEach { mol ->

						val info = molInfos.getOrPut(mol) { MolInfo(mol) }

						// show the molecule type
						checkbox("$mol##${System.identityHashCode(mol)}", info.pSelected)

						// show a context menu to center the camera on the molecule
						if (beginPopupContextItem("centerCamera${System.identityHashCode(mol)}")) {

							if (button("Center Camera")) {

								// center on the molecule centroid
								val center = Vector3d().apply {
									mol.atoms.forEach { add(it.pos) }
									div(mol.atoms.size.toDouble())
								}
								slidewin.camera.lookAt(center.toFloat())

								closeCurrentPopup()
							}

							endPopup()
						}

						// let the entries breathe a little
						spacing()
						spacing()
						separator()
						spacing()
						spacing()
					}

				val job = job
				if (job == null) {

					sliderInt("Num Steps", pNumSteps, 1, 1000)

					if (button("Minimize Selected Molecules")) {
						this@MinimizerTool.job = Job(
							molInfos.values.filter { it.pSelected.value },
							pNumSteps.value
						)
					}

				} else {

					text("Minimizing...")
					// TODO: show minimization progress
					// TODO: cancel button?

					if (job.isFinished.get()) {

						// report any errors
						job.throwable?.let { t ->
							slidewin.showExceptions { throw t }
						}

						// cleanup the finished job
						this@MinimizerTool.job = null
					}
				}

				end()

			},
			onClose = {
				// cleanup our mol infos
				molInfos.clear()
			}
		)
	}

	private class Job(
		val infos: List<MolInfo>,
		val numSteps: Int
	) {

		val isFinished = AtomicBoolean(false)
		var throwable: Throwable? = null

		val thread =
			Thread {

				try {
					infos.map { it.minInfo }.minimize(numSteps)
				} catch (t: Throwable) {
					throwable = t
				}

				// signal the thread is done
				isFinished.set(true)
			}
			.apply {
				name = "Minimizer"
			}
			.start()
	}
}
