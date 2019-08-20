package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.forcefield.amber.combine
import edu.duke.cs.ospreygui.io.toPDB
import edu.duke.cs.ospreygui.prep.Probe
import edu.duke.cs.ospreygui.view.ProbeView


class ClashViewer : SlideFeature {

	override val id = FeatureId("view.clashes")

	private val winState = WindowState()

	private val view = ProbeView()

	private class Counts(val pVisible: Ref<Boolean>) {
		var dots = 0
		var vectors = 0
	}
	private val countsByType = HashMap<String,Counts>()

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Clashes")) {
			winState.isOpen = true
		}
	}

	private fun Slide.Locked.molViews() = views.mapNotNull { it as? MoleculeRenderView }

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		val molViews = slide.molViews()

		winState.render(
			onOpen = {
				slide.views.add(view)
				slidewin.showExceptions {
					loadClashes(molViews)
				}
			},
			whenOpen = {

				// draw the window
				setNextWindowSize(300f, 0f)
				begin("Clashes##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize))

				if (button("Refresh")) {
					unloadClashes()
					loadClashes(molViews)
				}

				// show the counts, with toggles to show/hide
				if (countsByType.isNotEmpty()) {

					columns(2)
					for ((type, counts) in countsByType) {

						checkbox(type, counts.pVisible)
						nextColumn()

						if (counts.dots > 0) {
							text("${counts.dots} dots")
						} else if (counts.vectors > 0) {
							text("${counts.vectors} vectors")
						}
						nextColumn()
					}
					columns(1)

				} else {
					text("(no clash information)")
				}

				end()

			},
			onClose = {
				unloadClashes()
				slide.views.remove(view)
			}
		)
	}

	private fun loadClashes(views: List<MoleculeRenderView>) {

		// combine all the molecules into one PDB
		val pdb = views.map { it.mol }.combine("combined").toPDB()

		// run probe
		val results = Probe.run(pdb)
		view.groups = results.groups

		// update the counts
		countsByType.clear()
		for ((type, group) in results.groups) {
			countsByType
				.getOrPut(type) {
					Counts(Ref.of(
						getter = { view.visibility[type] },
						setter = { value -> view.visibility[type] = value }
					))
				}
				.apply {
					dots += group.dots.values.sumBy { it.size }
					vectors += group.vectors.values.sumBy { it.size }
				}
		}
	}

	private fun unloadClashes() {

		// clear probe results
		view.groups = null
	}
}