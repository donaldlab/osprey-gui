package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.gui.infoTip
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.osprey.tools.LZMA2
import edu.duke.cs.ospreygui.compiler.*
import edu.duke.cs.ospreygui.forcefield.Forcefield
import edu.duke.cs.ospreygui.forcefield.ForcefieldParams
import edu.duke.cs.ospreygui.forcefield.amber.AmberForcefieldParams
import edu.duke.cs.ospreygui.forcefield.eef1.EEF1ForcefieldParams
import edu.duke.cs.ospreygui.io.toBytes
import edu.duke.cs.ospreygui.io.write
import edu.duke.cs.ospreygui.prep.ConfSpace
import java.math.BigInteger
import java.nio.file.Path
import java.nio.file.Paths
import java.text.NumberFormat


class CompileConfSpace(val confSpace: ConfSpace) : SlideFeature {

	override val id = FeatureId("compile.confspace")

	companion object {
		const val textExtension = "ccs"
		const val compressedExtension = "ccs.xz"
	}

	// make the compiler and configure the default settings
	private val compiler = ConfSpaceCompiler(confSpace).apply {
		addForcefield(Forcefield.Amber96)
		addForcefield(Forcefield.EEF1)
	}

	private val bigIntFormatter = NumberFormat.getIntegerInstance()
		.apply {
			isGroupingUsed = true
		}
	private fun BigInteger.format() =
		bigIntFormatter.format(this)

	private val winState = WindowState()
	private val pCompressed = Ref.of(true)

	private var saveDir: Path = Paths.get(".").toAbsolutePath()
	private var progress: CompilerProgress? = null
	private var extraInfoBuf: Commands.TextBuffer = Commands.TextBuffer(1024)


	private fun resizeExtraInfoBufIfNeeded(msg: String): Commands.TextBuffer {

		if (!extraInfoBuf.hasRoomFor(msg)) {

			// make a bigger buffer
			extraInfoBuf = Commands.TextBuffer.of(msg)
		}

		return extraInfoBuf
	}

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Compile Conformation Space")) {
			winState.isOpen = true
		}
	}

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		// update any pending molecule views
		molsWatcher?.checkAll()

		winState.render(
			whenOpen = {

				// draw the window
				window("Compiler##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

					tabBar("tabs") {
						tabItem("Conf Space") {
							guiConfSpace(imgui)
						}
						tabItem("Forcefields") {
							guiForcefields(imgui)
						}
						tabItem("Net Charges") {
							guiNetCharges(imgui)
						}
					}

					spacing()
					spacing()
					separator()
					spacing()
					spacing()

					// show either the compile button, or compilation progress
					val progress = progress
					if (progress == null) {

						// no compile running already,
						// show a button to start the compilation
						if (button("Compile")) {
							compile(slide)
						}

					} else {

						val report = progress.report
						if (report == null) {

							// compilation running, show progress
							for ((taski, task) in progress.tasks.withIndex()) {

								if (taski > 0) {
									spacing()
								}

								progressBar(task.fraction, overlay = task.name)
							}

						} else {

							// compilation finished, show the report
							val compiled = report.compiled
							if (compiled != null) {
								compiled.guiSuccess(imgui, report.warnings)
							} else {
								report.guiError(imgui)
							}

							spacing()
							spacing()
							separator()
							spacing()
							spacing()

							// add a button to close the compilation report
							if (button("Finished")) {
								this@CompileConfSpace.progress = null
							}
						}
					}
				}
			}
		)
	}

	private fun guiConfSpace(imgui: Commands) = imgui.run {

		// show arbitrary stats about the conf space
		text("Conformation Space Info:")
		child("confSpaceInfo", 300f, 60f, true) {
			columns(2)

			text("Design Positions:")
			nextColumn()
			text("${confSpace.designPositionsByMol.values.sumBy { it.size }}")
			nextColumn()

			text("Sequences:")
			nextColumn()
			text(confSpace.positionConfSpaces.sequenceSpaceSize().format())
			nextColumn()

			text("Conformations:")
			nextColumn()
			text(confSpace.positionConfSpaces.confSpaceSize().format())
			nextColumn()

			columns(1)
		}
	}

	private var selectedForcefield: ForcefieldParams? = null

	private fun guiForcefields(imgui: Commands) = imgui.run {

		text("Forcefields:")
		child("forcefields", 200f, 100f, true) {
			for (ff in compiler.forcefields) {
				if (selectable(ff.forcefield.name, selectedForcefield === ff)) {
					selectedForcefield = ff
				}
			}
		}

		// if a forcefield is selected, show its settings
		selectedForcefield?.let { ff ->

			spacing()
			spacing()
			spacing()

			text("${ff.forcefield.name} settings:")
			indent(20f)

			when (ff) {

				is AmberForcefieldParams -> {

					inputDouble("Dielectric", Ref.of(ff::dielectric))
					sameLine()
					infoTip("""
						|The dielectric constant of the environment (aka its relative permittivity),
						|which influences electrostatic calculations
					""".trimMargin())

					checkbox("Distance-dependent dielectric", Ref.of(ff::distanceDependentDielectric))
					sameLine()
					infoTip("""
						|If checked, multiply the dielectric contstant by the atom pair distance (r)
						|for electrostatic interactions.
					""".trimMargin())

					inputDouble("van der Waals Scale", Ref.of(ff::vdwScale))
					sameLine()
					infoTip("""
						|Scaling to apply to the van der Waals calculations
					""".trimMargin())

					inputEnum("Charge Method", Ref.of(ff::chargeMethod))
					sameLine()
					infoTip("""
						|Method to generate partial charges for small molecules
					""".trimMargin())
				}

				is EEF1ForcefieldParams -> {

					inputDouble("Scale", Ref.of(ff::scale))
					sameLine()
					infoTip("""
						|Scaling to apply to the solvent forcefield energy
					""".trimMargin())
				}

				else -> Unit
			}

			unindent(20f)
		}
	}

	private fun guiNetCharges(imgui: Commands) = imgui.run {
		// TODO: let the user input net charges for small molecules
		//  or tell the user they don't have to
		text("TODO")
	}


	private class MoleculesWatcher(slide: Slide.Locked) {

		class Watcher(val view: MoleculeRenderView, sync: Any) {

			val locker = MoleculeLocker.Locker(view.mol, sync)

			private var sequence = 0

			fun check() {

				// did the sequence number update?
				val sequence = locker.sequence.get()
				if (this.sequence < sequence) {
					this.sequence = sequence

					// yup, tell the renderer
					view.moleculeChanged()
				}
			}
		}

		// make a watcher for each molecule render view.
		// and make the compiler synchronize on the unlocked slide,
		// since that's what the slide locking mechanism uses
		val watchers = slide.views
			.filterIsInstance<MoleculeRenderView>()
			.map { view -> Watcher(view, slide.unlocked) }

		fun checkAll() {
			for (watcher in watchers) {
				watcher.check()
			}
		}
	}
	private var molsWatcher: MoleculesWatcher? = null

	private fun compile(slide: Slide.Locked) {

		// make a molecules watcher so we can see when the compiler updates the molecules
		val molsWatcher = MoleculesWatcher(slide)
		this.molsWatcher = molsWatcher

		// make a molecule locker so we can keep the compiler thread
		// from racing the window thread when it modifies the molecules
		val molLocker = MoleculeLocker(molsWatcher.watchers.map { it.locker })

		progress = compiler.compile(molLocker)
	}

	private fun CompiledConfSpace.guiSuccess(imgui: Commands, warnings: List<CompilerWarning>) = imgui.run {

		// TODO: formatting? make it pretty?

		text("Conformation space compiled successfully!")

		// show compiler warnings
		if (warnings.isNotEmpty()) {
			text("Compiler warnings: ${warnings.size}")
			indent(20f)
			for (warning in warnings) {

				// show the warning
				text(warning.msg)

				// show a button to show more info, if needed
				warning.extraInfo?.let { extra ->
					// TODO
				}
			}
			unindent(20f)
		}

		// show the save button
		if (button("Save")) {

			val currentExtension =
				if (pCompressed.value) {
					compressedExtension
				} else {
					textExtension
				}

			val filterList = FilterList(listOf(currentExtension))
			FileDialog.saveFile(filterList, saveDir)?.let { chosenPath ->

				// decide if we should add an extension or not
				val filename = chosenPath.fileName.toString()
				val savePath = if (!filename.contains(".")) {

					// the user didn't choose an extension, so add one automatically
					chosenPath.parent.resolve("$filename.$currentExtension")

				} else {

					// otherwise, use exactly the filename the user gave us
					chosenPath
				}

				save(pCompressed.value, savePath)
			}
		}

		// show compression options
		sameLine()
		checkbox("Compress", pCompressed)
		sameLine()
		infoTip("""
			|Compiled conformation spaces can take up quite a bit of space.
			|Using compression will make the files significantly smaller,
			|and speed up transfers if you want to copy the compiled
			|conformation space files anywhere or send them over a network.
		""".trimMargin())
	}

	private fun CompiledConfSpace.save(compress: Boolean, path: Path) {

		// actually write out the compiled conf space to a file
		var bytes = toBytes()
		if (compress) {
			bytes = LZMA2.compress(bytes)
		}
		bytes.write(path)
	}

	private fun ConfSpaceCompiler.Report.guiError(imgui: Commands) = imgui.run gui@{

		// TODO: formatting? make it pretty?

		text("Conformation space failed to compile.")

		val error = error ?: run {
			// this shouldn't happen, but just in case ...
			text("but no error information was available")
			return@gui
		}

		// TODO: show icon
		text("(E)")
		sameLine()
		text(error.message ?: "(no error message was provided)")

		error.extraInfo?.let { msg ->

			inputTextMultiline(
				"Extra error information",
				resizeExtraInfoBufIfNeeded(msg),
				600f, 400f,
				IntFlags.of(Commands.InputTextFlags.ReadOnly)
			)
		}
	}
}
