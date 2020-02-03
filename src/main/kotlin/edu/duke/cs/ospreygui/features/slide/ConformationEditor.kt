package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import cuchaz.kludge.tools.toRadians
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.*
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.motions.DihedralAngle
import edu.duke.cs.ospreygui.features.components.ConfLibPicker
import edu.duke.cs.ospreygui.features.components.DesignPositionEditor
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.io.confRuntimeId
import edu.duke.cs.ospreygui.io.fragRuntimeId
import edu.duke.cs.ospreygui.motions.TranslationRotation
import edu.duke.cs.ospreygui.prep.ConfSpace
import edu.duke.cs.ospreygui.prep.ConfSpacePrep
import edu.duke.cs.ospreygui.prep.DesignPosition
import org.joml.Vector3d
import java.math.BigInteger
import java.text.NumberFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.random.asKotlinRandom
import kotlin.random.Random


class ConformationEditor(val prep: ConfSpacePrep) : SlideFeature {

	override val id = FeatureId("edit.conformations")

	private inner class ConfEditor(val molInfo: MolInfo, val posInfo: PosInfo) {

		val winState = WindowState()
			.apply { pOpen.value = true }
		val posEditor = DesignPositionEditor(prep, posInfo.pos)
		var resetTabSelection = true
		val discreteTabState = Commands.TabState()
		val continuousTabState = Commands.TabState()
		val conflibPicker = ConfLibPicker(prep).apply {
			onAdd = { resetInfos() }
		}

		val pDihedralRadiusDegrees = Ref.of(
			getter = { posEditor.dihedralSettings.radiusDegrees.toFloat() },
			setter = { posEditor.dihedralSettings.radiusDegrees = it.toDouble() }
		)
		val pDihedralIncludeHydroxyls = Ref.of(posEditor.dihedralSettings::includeHydroxyls)
		val pDihedralIncludeNonHydroxylHGroups = Ref.of(posEditor.dihedralSettings::includeNonHydroxylHGroups)

		inner class MutInfo(val type: String) {

			inner class FragInfo(val conflib: ConfLib?, val frag: ConfLib.Fragment) {

				val mutInfo get() = this@MutInfo

				val id = conflib.fragRuntimeId(frag)

				inner class ConfInfo(val conf: ConfLib.Conf) {

					val fragInfo get() = this@FragInfo

					val id = conflib.confRuntimeId(frag, conf)
					val label = "${conf.name}##$id"

					val pSelected = Ref.of(posInfo.confSpace.confs.contains(frag, conf))

					fun add() {
						posInfo.confSpace.confs.add(frag, conf).run {

						// add default dihedral angles from the conformation library
						DihedralAngle.ConfDescription.makeFromLibrary(posInfo.pos, frag, conf, posEditor.dihedralSettings)
							.forEach { motions.add(it) }
						}
					}

					fun remove() = posInfo.confSpace.confs.remove(frag, conf)
					fun get() = posInfo.confSpace.confs.get(frag, conf)
					fun included() = posInfo.confSpace.confs.contains(frag, conf)
				}

				// collect the conformations
				val confInfos = frag.confs
					.values
					.map { ConfInfo(it) }

				val numSelected = confInfos.count { it.pSelected.value }
				val numPossible = frag.confs.size

				val label = "${frag.name}, $numSelected/$numPossible confs###${posInfo.pos.name}-$id"
			}

			// collect the fragments from the conf libs
			val fragInfos = prep.conflibs
				.flatMap { conflib -> conflib.fragments.values.map { conflib to it } }
				.toMutableList()
				.let { frags ->
					// prepend the wild-type fragment, if any
					val wtFrag = posInfo.confSpace.wildTypeFragment
					if (wtFrag != null) {
						listOf(null to wtFrag) + frags
					} else {
						frags
					}
				}
				.filter { (_, frag) -> frag.type == type && posInfo.pos.isFragmentCompatible(frag) }
				.map { (conflib, frag) -> FragInfo(conflib, frag) }

			val numSelected = fragInfos.sumBy { it.numSelected }
			val numConfs = fragInfos.sumBy { it.numPossible }

			val label = "$type, $numSelected/$numConfs confs###${posInfo.pos.name}-$type"
		}
		val mutInfos = ArrayList<MutInfo>()
		var selectedFragInfo: MutInfo.FragInfo? = null
		var selectedConfInfo: MutInfo.FragInfo.ConfInfo? = null

		val motionInfos = ArrayList<MotionInfo>()
		val pJiggle = Ref.of(false)

		@Suppress("RemoveRedundantQualifierName") // this qualifier is not redundant. IntelliJ is wrong. ;_;
		val rand = java.util.Random().asKotlinRandom()

		fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

			val view = slide.views
				.filterIsInstance<MoleculeRenderView>()
				.find { it.mol == molInfo.mol }
				?: throw Error("can't init design position, molecule has no render view")

			winState.render(
				onOpen = {

					// init the pos editor
					posEditor.refresh(view)
				},
				whenOpen = {

					// draw the window
					window("Edit ${posInfo.pos.name} conformations##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

						// if we need to reset the tab selection, make the flags for the first tab
						fun makeFlags() =
							if (resetTabSelection) {
								resetTabSelection = false
								IntFlags.of(Commands.TabItemFlags.SetSelected)
							} else {
								IntFlags.of(Commands.TabItemFlags.None)
							}

						tabBar("tabs") {

							// only show the position editing gui for flexible positions
							if (posInfo.isFlexible) {
								when (molInfo.molType) {
									MoleculeType.Protein -> tabItem("Protein", flags = makeFlags()) {
										posEditor.guiProtein(imgui, slidewin, view)
									}
									// TODO: others?
									else -> Unit
								}
								tabItem("Atoms", flags = makeFlags()) {
									posEditor.guiAtoms(imgui, slidewin, view)
								}
							}

							tabItem(discreteTabState, "Discrete", flags = makeFlags(),
								onActivated = {
									activateDiscreteTab()
								},
								whenActive = {
									renderDiscreteTab(imgui, slidewin, view)
								},
								onDeactivated = {
									deactivateDiscreteTab(view)
								}
							)

							tabItem(continuousTabState, "Continuous", flags = makeFlags(),
								onActivated = {
									activateContinuousTab(view)
								},
								whenActive = {
									renderContinuousTab(imgui, slidewin, view)
								},
								onDeactivated = {
									deactivateContinuousTab(view)
								}
							)
						}
					}
				},
				onClose = {

					// deactivate any tabs if they're active
					if (discreteTabState.wasActive) {
						discreteTabState.wasActive = false
						deactivateDiscreteTab(view)
					}
					if (continuousTabState.wasActive) {
						continuousTabState.wasActive = false
						deactivateContinuousTab(view)
					}

					// cleanup the pos editor
					posEditor.closed()
					confEditor = null
				}
			)
		}

		fun resetInfos() {
			mutInfos.clear()
			for (mut in posInfo.confSpace.mutations) {
				mutInfos.add(MutInfo(mut))
			}
		}

		fun activateDiscreteTab() {

			resetInfos()

			// find the wildtype conf info, if possible, and select it by default
			val wtConf = posInfo.confSpace.wildTypeFragment
				?.confs
				?.values
				?.firstOrNull()
			selectedConfInfo = mutInfos
				.flatMap { it.fragInfos.flatMap { it.confInfos } }
				.find { it.conf === wtConf }
		}

		fun renderDiscreteTab(imgui: Commands, slidewin: SlideCommands, view: MoleculeRenderView) = imgui.run {

			// show the conflib picker
			conflibPicker.render(imgui)

			var changed = false

			text("Conformations:")
			sameLine()
			infoTip("""
				|Select the conformations you want to include in your design by clicking the checkboxes.
				|You can temporarily preview a conformation by selecting the radion button next to it.
				|All temporary confomrations will be reverted when you're finished with the conformation editor.
			""".trimMargin())
			child("confs", 300f, 400f, true) {
				for (mutInfo in mutInfos) {
					treeNode(mutInfo.label, flags = IntFlags.of(Commands.TreeNodeFlags.DefaultOpen)) {
						for (fragInfo in mutInfo.fragInfos) {
							treeNode(fragInfo.label) {
								for (confInfo in fragInfo.confInfos) {

									if (radioButton("##radio-${confInfo.id}", selectedConfInfo === confInfo)) {
										selectedConfInfo = confInfo
										setConf(view, fragInfo.frag, confInfo.conf)
									}
									sameLine()
									if (checkbox(confInfo.label, confInfo.pSelected)) {

										// mark the conf as included or not in the design position conf space
										if (confInfo.pSelected.value) {
											confInfo.add()
										} else {
											confInfo.remove()
										}

										changed = true
									}
								}
							}
						}
					}
				}
				if (mutInfos.isEmpty()) {
					text("(No compatible conformations)")
				}
			}

			if (button("Select all")) {
				for (mutInfo in mutInfos) {
					for (fragInfo in mutInfo.fragInfos) {
						for (confInfo in fragInfo.confInfos) {
							if (!confInfo.included()) {
								confInfo.add()
							}
						}
					}
				}
				changed = true
			}
			sameLine()
			if (button("Deselect all")) {
				for (mutInfo in mutInfos) {
					for (fragInfo in mutInfo.fragInfos) {
						for (confInfo in fragInfo.confInfos) {
							confInfo.remove()
						}
					}
				}
				changed = true
			}

			// apply changes if needed
			if (changed) {
				resetInfos()
				updateCounts()
			}
		}

		fun deactivateDiscreteTab(view: MoleculeRenderView) {

			// restore the wildtype if needed
			posInfo.confSpace.wildTypeFragment?.let { setConf(view, it, it.confs.values.first()) }
		}

		private fun setConf(view: MoleculeRenderView, frag: ConfLib.Fragment, conf: ConfLib.Conf) {

			posInfo.pos.setConf(frag, conf)

			// update the view
			posEditor.refresh(view)
			view.moleculeChanged()
		}

		private fun selectConf(view: MoleculeRenderView, fragInfo: MutInfo.FragInfo, confInfo: MutInfo.FragInfo.ConfInfo) {

			selectedFragInfo = fragInfo
			selectedConfInfo = confInfo

			setConf(view, fragInfo.frag, confInfo.conf)

			resetMotionInfos()
		}

		fun activateContinuousTab(view: MoleculeRenderView) {

			resetInfos()

			// find the wildtype fragment, if possible, and select it by default
			val wtFragInfo = posInfo.confSpace.wildTypeFragment?.let { frag ->
				for (mutInfo in mutInfos) {
					for (fragInfo in mutInfo.fragInfos) {
						if (fragInfo.frag == frag) {
							return@let fragInfo
						}
					}
				}
				return@let null
			}
			val wtConfInfo = wtFragInfo
				?.confInfos
				?.firstOrNull()
			if (wtFragInfo != null && wtConfInfo != null) {
				selectConf(view, wtFragInfo, wtConfInfo)
			} else {
				selectedFragInfo = null
				selectedConfInfo = null
				resetMotionInfos()
			}
		}

		fun resetMotionInfos() {

			motionInfos.clear()

			// get the selected fragment,conformation
			val fragInfo = selectedFragInfo ?: return
			val confInfo = selectedConfInfo ?: return
			val space = confInfo.get() ?: return

			for (motion in fragInfo.frag.motions) {
				when (motion) {

					is ConfLib.ContinuousMotion.DihedralAngle -> {
						motionInfos.add(MotionInfo.DihedralInfo(
							motionInfos.size,
							motion,
							posInfo,
							space,
							posEditor.dihedralSettings.radiusDegrees
						))
					}
				}
			}
		}

		fun renderContinuousTab(imgui: Commands, slidewin: SlideCommands, view: MoleculeRenderView) = imgui.run {

			// HACKHACK: ImGUI doesn't want to resize the window automatically for this tab for some reason
			// so put a prop of a certain size to push out the width of the window
			child("foo", 600f, 1f) {}

			columns(2) {
				column(300f) { // frags/confs column

					text("Choose a fragment:")
					child("testFrags", 280f, 270f, true) {

						var hasSelections = false

						for (mutInfo in mutInfos) {
							for (fragInfo in mutInfo.fragInfos) {

								// only show fragments with selected confs
								val firstConfInfo = fragInfo.confInfos
									.firstOrNull { it.pSelected.value }
									?: continue

								if (radioButton("${fragInfo.frag.name}##fragRadio-${fragInfo.id}", selectedFragInfo === fragInfo)) {
									selectConf(view, fragInfo, firstConfInfo)
								}

								hasSelections = true
							}
						}

						if (!hasSelections) {
							text("No fragments to show here.")
							text("Try including some conformations")
							text("at this design position.")
						}
					}

					text("Choose a conformation:")
					child("testConfs", 280f, 270f, true) {
						val fragInfo = selectedFragInfo
						if (fragInfo != null) {
							for (confInfo in fragInfo.confInfos) {

								// only show selected confs
								if (!confInfo.included()) {
									continue
								}

								if (radioButton("${confInfo.conf.name}##confRadio-${confInfo.id}", selectedConfInfo === confInfo)) {
									selectConf(view, fragInfo, confInfo)
								}
							}
						}
					}
				}
				column(300f) col@{ // motions column

					val confInfo = selectedConfInfo ?: return@col

					text("Dihedral Angle Settings:")
					indent(20f)

					text("Angle radius (in degrees)")
					sameLine()
					infoTip("""
						|This value specifies the half-width (ie radius) of the interval of allowed angles.
					""".trimMargin())

					sliderFloat("##dihedralRadius", pDihedralRadiusDegrees, 0f, 180f, "%.1f")
					sameLine()
					infoTip("Ctrl-click to type a precise value")

					spacing()

					checkbox("Include Hydroxyls", pDihedralIncludeHydroxyls)

					spacing()

					checkbox("Include Non-Hydroxyl H-groups", pDihedralIncludeNonHydroxylHGroups)
					sameLine()
					infoTip("e.g. Methyls, Methylenes")

					fun ConfSpace.ConfConfSpace.resetDihedralAngles() {

						// recreate all the dihedral angles with the new settings
						motions.removeIf { it is DihedralAngle.ConfDescription }
						motions.addAll(DihedralAngle.ConfDescription.makeFromLibrary(
							posInfo.pos,
							frag,
							conf,
							posEditor.dihedralSettings
						))
					}

					if (button("Reset ${confInfo.fragInfo.frag.name}/${confInfo.conf.name}")) {
						confInfo.get()?.resetDihedralAngles()
						resetMotionInfos()
					}
					sameLine()
					infoTip("Resets dihedral angles for just this selected conformation.")

					if (button("Reset ${confInfo.fragInfo.frag.name}")) {
						confInfo.fragInfo.confInfos
							.mapNotNull { it.get() }
							.forEach { it.resetDihedralAngles() }
						resetMotionInfos()
					}
					sameLine()
					infoTip("Resets dihedral angles for all conformations in this selected fragment.")

					if (button("Reset ${confInfo.fragInfo.mutInfo.type}")) {
						confInfo.fragInfo.mutInfo.fragInfos
							.flatMap { it.confInfos }
							.mapNotNull { it.get() }
							.forEach { it.resetDihedralAngles() }
						resetMotionInfos()
					}
					sameLine()
					infoTip("Resets dihedral angles for all conformations in this selected mutation.")

					if (button("Reset design position")) {
						mutInfos
							.flatMap { it.fragInfos }
							.flatMap { it.confInfos }
							.mapNotNull { it.get() }
							.forEach { it.resetDihedralAngles() }
						resetMotionInfos()
					}
					sameLine()
					infoTip("Resets dihedral angles for all conformations in this design position.")


					unindent(20f)

					spacing()

					text("Degrees of Freedom for: ${confInfo.fragInfo.frag.name}/${confInfo.conf.name}")
					indent(20f)

					if (motionInfos.isNotEmpty()) {

						for ((i, motionInfo) in motionInfos.withIndex()) {

							// breathe a little
							spacing()
							spacing()
							separator()
							spacing()
							spacing()

							withId(i) {
								text(motionInfo.label)
								motionInfo.gui(imgui, view)
							}
						}

						spacing()
						spacing()
						separator()
						spacing()
						spacing()

						// this is my new favorite button! =D
						checkbox("Jiggle", pJiggle)
						sameLine()
						infoTip("""
							|Randomly sample all the degrees of freedom over time,
							|to get a sense of the the range of conformations accessible
							|to the design position.
						""".trimMargin())

						if (pJiggle.value) {
							motionInfos.forEach { it.jiggle(rand, view) }
						}

					} else {

						text("(No degrees of freedom found)")
					}

					unindent(20f)
				}
			}
		}

		fun deactivateContinuousTab(view: MoleculeRenderView) {

			// restore the wildtype if needed
			posInfo.confSpace.wildTypeFragment?.let { setConf(view, it, it.confs.values.first()) }

			// cleanup
			motionInfos.clear()
		}
	}

	private var confEditor: ConfEditor? = null
	private val winState = WindowState()

	private inner class MolInfo(val mol: Molecule, val molType: MoleculeType) {

		val label = mol.toString()
		val posInfos = ArrayList<PosInfo>()
		var numConfs: BigInteger = BigInteger.ZERO

		val motions get() =
			prep.confSpace.molMotions
				.getOrPut(mol) { ArrayList() }

		var transRotInfo = motions
			.filterIsInstance<TranslationRotation.MolDescription>()
			.firstOrNull()
			?.let { MotionInfo.TranslationRotationInfo(it) }
		val useTransRot = Ref.of(transRotInfo != null)

		fun updateCounts() {
			numConfs = BigInteger.ONE
			for (posInfo in posInfos) {
				numConfs *= posInfo.confSpace.confs.size.toBigInteger()
			}
		}

		fun makeNewPosition(): PosInfo {

			val positions = prep.confSpace.designPositionsByMol
				.getOrPut(mol) { ArrayList() }

			// choose a default but unique name
			val prefix = "Pos "
			val maxNum = positions
				.mapNotNull {
					it.name
						.takeIf { it.startsWith(prefix) }
						?.substring(prefix.length)
						?.toIntOrNull()
				}
				.max()
				?: 0
			val num = maxNum + 1

			// create the position and add it
			val pos = DesignPosition("$prefix$num", "none", mol)
			positions.add(pos)

			val posInfo = PosInfo(pos)
			posInfos.add(posInfo)
			return posInfo
		}
	}

	inner class PosInfo(val pos: DesignPosition) {

		val pSelected = Ref.of(false)

		val confSpace get() = prep.confSpace.positionConfSpaces.getOrMake(pos)
		val isMutable get() = confSpace.isMutable()
		val isFlexible get() = !isMutable
	}

	private val molInfos = IdentityHashMap<Molecule,MolInfo>()
	private var numConfs: BigInteger = BigInteger.ZERO
	private val confFormatter = NumberFormat.getIntegerInstance()
		.apply {
			isGroupingUsed = true
		}

	private fun updateCounts() {
		if (molInfos.isEmpty()) {
			numConfs = BigInteger.ZERO
		} else {
			numConfs = BigInteger.ONE
			for (molInfo in molInfos.values) {
				molInfo.updateCounts()
				numConfs *= molInfo.numConfs
			}
		}
	}

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Flexibility")) {
			winState.isOpen = true
		}
	}

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		winState.render(
			onOpen = {

				// update infos
				molInfos.clear()
				for ((moltype, mol) in prep.confSpace.mols) {
					molInfos.getOrPut(mol) { MolInfo(mol, moltype) }.apply {
						prep.confSpace.designPositionsByMol[mol]?.forEach { pos ->
							posInfos.add(PosInfo(pos))
						}
					}
				}

				updateCounts()
			},
			whenOpen = {

				val views = slide.views.filterIsInstance<MoleculeRenderView>()

				// draw the window
				window("Flexibility Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

					tabBar("tabs") {

						for (molInfo in molInfos.values) {
							tabItem(molInfo.label) {

								text("Mutable Positions:")
								spacing()
								columns(2)
								indent(20f)
								val mutInfos = molInfo.posInfos.filter { it.isMutable }
								for (posInfo in mutInfos) {

									selectable(posInfo.pos.name, posInfo.pSelected)
									nextColumn()
									text("${posInfo.confSpace.confs.size} confs")
									nextColumn()
								}
								columns(1)
								if (mutInfos.isEmpty()) {
									text("(no mutable positions)")
								}
								unindent(20f)
								spacing()

								text("Flexible Positions:")
								child("flexpos", 300f, 200f, true) {
									columns(2)
									for (flexInfo in molInfo.posInfos.filter { it.isFlexible }) {

										selectable(flexInfo.pos.name, flexInfo.pSelected)
										nextColumn()
										text("${flexInfo.confSpace.confs.size} confs")
										nextColumn()
									}
									columns(1)
								}

								if (button("Add")) {

									// start the conformation editor
									confEditor = ConfEditor(molInfo, molInfo.makeNewPosition())
								}

								sameLine()

								val canEdit = molInfo.posInfos.count { it.pSelected.value } == 1
								styleEnabledIf(canEdit) {
									if (button("Edit") && canEdit) {

										// start the conformation editor
										molInfo.posInfos
											.find { it.pSelected.value }
											?.let {
												confEditor = ConfEditor(molInfo, it)
											}
									}
								}

								sameLine()

								// allow removing flexible positions only
								styleEnabledIf(molInfo.posInfos.any { it.pSelected.value && it.isFlexible }) {
									if (button("Remove")) {
										molInfo.posInfos
											.filter { it.pSelected.value && it.isFlexible }
											.forEach {
												molInfo.posInfos.remove(it)
												prep.confSpace.designPositionsByMol[molInfo.mol]?.remove(it.pos)
												prep.confSpace.positionConfSpaces.remove(it.pos)
											}
										updateCounts()
									}
								}

								spacing()

								text("Conformations: ${confFormatter.format(molInfo.numConfs)}")

								spacing()
								spacing()
								spacing()

								val view = views
									.find { it.mol == molInfo.mol }
									?: throw Error("can't show motions GUI, molecule has no render view")

								// show translation and rotation options
								if (checkbox("Translate and Rotate", molInfo.useTransRot)) {

									// remove any existing translations
									molInfo.motions.removeIf { it is TranslationRotation.MolDescription }

									if (molInfo.useTransRot.value) {

										// add the translation and rotation with default settings
										val desc = TranslationRotation.MolDescription(
											molInfo.mol,
											maxTranslationDist = 1.2, // angstroms
											maxRotationDegrees = 5.0
										)
										molInfo.motions.add(desc)
										molInfo.transRotInfo = MotionInfo.TranslationRotationInfo(desc)

									} else {

										// cleanup the translation and rotation motion
										molInfo.transRotInfo?.reset(view)
										molInfo.transRotInfo = null
									}
								}
								molInfo.transRotInfo?.let { transRotInfo ->
									// TODO: put this GUI somewhere else?

									indent(20f)

									if (inputDouble("Max Distance (Angstroms)", Ref.of(transRotInfo.desc::maxTranslationDist))) {
										transRotInfo.updateBounds()
									}
									if (inputDouble("Max Rotation (Degrees)", Ref.of(transRotInfo.desc::maxRotationDegrees))) {
										transRotInfo.updateBounds()
									}

									spacing()
									spacing()
									spacing()

									transRotInfo.gui(imgui, view)

									unindent(20f)
								}
							}
						}
					}

					// for multiple molecules, show the combined conformation count
					if (molInfos.size > 1) {
						spacing()
						text("Combined Conformations: ${confFormatter.format(numConfs)}")
					}

					// render the conformation editor, when active
					confEditor?.gui(imgui, slide, slidewin)
				}
			},
			onClose = {

				// reset translations and rotations
				val views = slide.views.filterIsInstance<MoleculeRenderView>()
				for (molInfo in molInfos.values) {
					views
						.find { it.mol == molInfo.mol }
						?.let { view ->
							molInfo.transRotInfo?.reset(view)
						}
				}

				// cleanup infos
				molInfos.clear()
			}
		)
	}


	private sealed class MotionInfo {

		abstract val label: String
		abstract fun gui(imgui: Commands, view: MoleculeRenderView)
		abstract fun jiggle(rand: Random, view: MoleculeRenderView)

		class DihedralInfo(
			val id: Int,
			val motion: ConfLib.ContinuousMotion.DihedralAngle,
			val posInfo: PosInfo,
			val space: ConfSpace.ConfConfSpace,
			val defaultRadiusDegrees: Double
		) : MotionInfo() {

			fun getDescription() =
				space
					.motions
					.filterIsInstance<DihedralAngle.ConfDescription>()
					.find { it.motion === motion }

			val pIncluded = Ref.of(getDescription() != null)

			inner class ActiveAngle(val desc: DihedralAngle.ConfDescription) {

				val dihedral = desc.make()

				val minValue = desc.minDegrees.toFloat()
				val maxValue = desc.maxDegrees.toFloat()
				val radius = (maxValue - minValue)/2f

				// start in the center of the interval
				val pValue = Ref.of((minValue + maxValue)/2f)

				// copy the original coordinates
				val coords = dihedral.rotatedAtoms.map { Vector3d(it.pos) }

				fun reset() {
					// restore the original coordinates
					for (i in coords.indices) {
						dihedral.rotatedAtoms[i].pos.set(coords[i])
					}
				}
			}

			var activeAngle =
				getDescription()?.let { desc -> ActiveAngle(desc) }

			override val label = run {
				val match = posInfo.pos.findAnchorMatch(space.frag)
					?: return@run "Dihedral Angle"
				val atomNames = listOf(motion.a, motion.b, motion.c, motion.d)
					.map { match.resolveName(it) }
				"Dihedral Angle: ${atomNames.joinToString(", ")}"
			}

			override fun gui(imgui: Commands, view: MoleculeRenderView): Unit = imgui.run {

				// show a checkbox to enable/disable the dihedral angle
				if (checkbox("Included", pIncluded)) {
					if (pIncluded.value) {

						// add the motion, with default settings
						val desc = DihedralAngle.ConfDescription.make(posInfo.pos, motion, space.conf, defaultRadiusDegrees)
						space.motions.add(desc)
						activeAngle = ActiveAngle(desc).apply {
							dihedral.setDegrees(pValue.value.toDouble())
						}
						view.moleculeChanged()

					} else {

						// remove the motion
						space.motions.removeIf {
							(it as? DihedralAngle.ConfDescription)?.motion === motion
						}

						activeAngle?.reset()
						activeAngle = null
						view.moleculeChanged()
					}
				}

				// show a slider to manipulate the dihedral angle
				activeAngle?.run {
					text("Radius: %.1f degrees".format(radius))
					text("Range: %.1f to %.1f degrees".format(minValue, maxValue))
					if (sliderFloat("Angle##motion-$id", pValue, minValue, maxValue, format = "%.1f")) {
						dihedral.setDegrees(pValue.value.toDouble())
						view.moleculeChanged()
					}
				}
			}

			override fun jiggle(rand: Random, view: MoleculeRenderView) {
				activeAngle?.run {
					pValue.value = rand.nextFloatIn(minValue, maxValue)
					dihedral.setDegrees(pValue.value.toDouble())
					view.moleculeChanged()
				}
			}
		}

		class TranslationRotationInfo(val desc: TranslationRotation.MolDescription) : MotionInfo() {

			val transRot = desc.make()

			val pPsi = Ref.of(0.0f)
			val pTheta = Ref.of(0.0f)
			val pPhi = Ref.of(0.0f)
			val px = Ref.of(0.0f)
			val py = Ref.of(0.0f)
			val pz = Ref.of(0.0f)

			var rmax = 0.0f
			var rmin = 0.0f
			var tmax = 0.0f
			var tmin = 0.0f

			init {
				updateBounds()
			}

			override val label = "Translation and Rotation of ${desc.mol}"

			fun updateBounds() {
				rmax = desc.maxRotationDegrees.toFloat()
				rmin = -rmax
				tmax = desc.maxTranslationDist.toFloat()
				tmin = -tmax
			}

			private fun updateMol(view: MoleculeRenderView) {
				transRot.set(
					pPsi.value.toDouble().toRadians(),
					pTheta.value.toDouble().toRadians(),
					pPhi.value.toDouble().toRadians(),
					px.value.toDouble(),
					py.value.toDouble(),
					pz.value.toDouble()
				)
				view.moleculeChanged()
			}

			override fun gui(imgui: Commands, view: MoleculeRenderView) = imgui.run {

				text("Tait-Bryan Rotation:")
				if (sliderFloat("Psi (X)", pPsi, rmin, rmax, "%.3f")) {
					updateMol(view)
				}
				if (sliderFloat("Theta (Y)", pTheta, rmin, rmax, "%.3f")) {
					updateMol(view)
				}
				if (sliderFloat("Phi (Z)", pPhi, rmin, rmax, "%.3f")) {
					updateMol(view)
				}

				text("Cartesian Translation:")
				if (sliderFloat("X", px, tmin, tmax, "%.3f")) {
					updateMol(view)
				}
				if (sliderFloat("Y", py, tmin, tmax, "%.3f")) {
					updateMol(view)
				}
				if (sliderFloat("Z", pz, tmin, tmax, "%.3f")) {
					updateMol(view)
				}
			}

			override fun jiggle(rand: Random, view: MoleculeRenderView) {

				pPsi.value = rand.nextFloatIn(rmin, rmax)
				pTheta.value = rand.nextFloatIn(rmin, rmax)
				pPhi.value = rand.nextFloatIn(rmin, rmax)

				px.value = rand.nextFloatIn(tmin, tmax)
				py.value = rand.nextFloatIn(tmin, tmax)
				pz.value = rand.nextFloatIn(tmin, tmax)

				updateMol(view)
			}

			fun reset(view: MoleculeRenderView) {
				transRot.reset()
				view.moleculeChanged()
			}
		}
	}
}

private fun Random.nextFloatIn(min: Float, max: Float): Float =
	if (min != max) {
		nextDouble(min.toDouble(), max.toDouble()).toFloat()
	} else {
		min
	}
