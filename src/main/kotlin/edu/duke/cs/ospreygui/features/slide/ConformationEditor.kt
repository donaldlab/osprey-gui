package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.*
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.molecule.Element
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.tools.identityHashSet
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.motions.DihedralAngle
import edu.duke.cs.ospreygui.motions.dihedralAngle
import edu.duke.cs.ospreygui.motions.supportsDihedralAngle
import edu.duke.cs.ospreygui.features.components.ConfLibPicker
import edu.duke.cs.ospreygui.features.components.DesignPositionEditor
import edu.duke.cs.ospreygui.features.components.default
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.io.confRuntimeId
import edu.duke.cs.ospreygui.io.fragRuntimeId
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

		val pIncludeHGroupRotations = Ref.of(false)
		val pDihedralRadiusDegrees = Ref.of(0f)

		inner class MutInfo(val type: String) {

			inner class FragInfo(val conflib: ConfLib?, val frag: ConfLib.Fragment) {

				val id = conflib.fragRuntimeId(frag)
				val confs = posInfo.confSpace.confs
					.getOrPut(frag) { identityHashSet() }
				val motionSettings = posInfo.confSpace.motionSettings
					.getOrPut(frag) { ConfSpace.PositionConfSpace.MotionSettings.default() }

				inner class ConfInfo(val conf: ConfLib.Conf) {

					val id = conflib.confRuntimeId(frag, conf)
					val label = "${conf.name}##$id"

					val pSelected = Ref.of(conf in confs)
				}

				// collect the conformations
				val confInfos = frag.confs
					.values
					.map { ConfInfo(it) }

				val numSelected = confInfos.count { it.pSelected.value }
				val numPossible = frag.confs.size

				val label = "${frag.name}, $numSelected/$numPossible confs###$id"
			}

			// collect the fragments
			val fragInfos = prep.conflibs
				.flatMap { conflib ->
					conflib.fragments
						.values
						.filter { it.type == type && posInfo.pos.isFragmentCompatible(it) }
						.map { FragInfo(conflib, it) }
				}
				.let { fragInfos ->

					// do we have a compatible wildtype fragment?
					val wtFrag = posInfo.confSpace.wildTypeFragment
						.takeIf { it?.type == type && posInfo.pos.isFragmentCompatible(it) }
					return@let if (wtFrag != null) {
						// yup, prepend it
						listOf(FragInfo(null, wtFrag)) + fragInfos
					} else {
						// nope, keep the current fragments
						fragInfos
					}
				}

			val numSelected = fragInfos.sumBy { it.numSelected }
			val numConfs = fragInfos.sumBy { it.numPossible }

			val label = "$type, $numSelected/$numConfs confs###$type"
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
					window("Design Position Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

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
					treeNode(mutInfo.label) {
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
											fragInfo.confs.add(confInfo.conf)
										} else {
											fragInfo.confs.remove(confInfo.conf)
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
						fragInfo.confs.addAll(fragInfo.confInfos.map { it.conf })
					}
				}
				changed = true
			}
			sameLine()
			if (button("Deselect all")) {
				for (mutInfo in mutInfos) {
					for (fragInfo in mutInfo.fragInfos) {
						fragInfo.confs.clear()
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

			val fragInfo = selectedFragInfo ?: return
			val confInfo = selectedConfInfo ?: return

			// find the motion settings for this fragment
			selectedFragInfo?.motionSettings?.let { motionSettings ->
				pIncludeHGroupRotations.value = motionSettings.includeHGroupRotations
				pDihedralRadiusDegrees.value = motionSettings.dihedralRadiusDegrees.toFloat()
			}

			val match = posInfo.pos.findAnchorMatch(fragInfo.frag)
				?: throw RuntimeException("no anchor match")

			// get the coords for an atom pointer
			fun ConfLib.AtomPointer.resolveCoords(): Vector3d =
				when (this) {
					is ConfLib.AtomInfo -> {
						// easy peasy
						confInfo.conf.coords.getValue(this)
					}
					is ConfLib.AnchorAtomPointer -> {
						// a bit more work ...
						val posAnchor = match.findPosAnchor(anchor)
							?: throw RuntimeException("no matched anchor")
						posAnchor.anchorAtoms[index].pos
					}
					else -> throw IllegalArgumentException("unrecognized atom pointer type: ${this::class.simpleName}")
				}


			// build the continuous motions for the selected fragment, if any
			motions@for (motion in fragInfo.frag.motions) {
				when (motion) {
					is ConfLib.ContinuousMotion.DihedralAngle -> {

						// filter out h-group rotations if needed
						val isHGroupRotation = motion.affectedAtoms(fragInfo.frag)
							.let { atoms ->
								atoms.isNotEmpty() && atoms.all { it.element == Element.Hydrogen }
							}
						if (!pIncludeHGroupRotations.value && isHGroupRotation) {
							continue@motions
						}

						motionInfos.add(MotionInfo.DihedralInfo(
							motionInfos.size,
							posInfo.pos,
							motion,
							initialDegrees = DihedralAngle.measureDegrees(
								motion.a.resolveCoords(),
								motion.b.resolveCoords(),
								motion.c.resolveCoords(),
								motion.d.resolveCoords()
							),
							radiusDegrees = pDihedralRadiusDegrees.value.toDouble()
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
				column(300f) { // settings/frags/confs column

					text("Choose a fragment to test:")
					child("testFrags", 280f, 200f, true) {
						for (mutInfo in mutInfos) {
							for (fragInfo in mutInfo.fragInfos) {

								// only show fragments with selected confs
								val firstConfInfo = fragInfo.confInfos
									.firstOrNull { it.pSelected.value }
									?: continue

								if (radioButton("${fragInfo.frag.name}##fragRadio-${fragInfo.id}", selectedFragInfo === fragInfo)) {
									selectConf(view, fragInfo, firstConfInfo)
								}
							}
						}
					}

					val fragInfo = selectedFragInfo
					if (fragInfo != null) {

						spacing()

						text("${fragInfo.frag.name} Settings:")
						child("settings", border = true) {

							text("Dihedral angle radius (in degrees)")
							sameLine()
							infoTip("""
								|For degrees of freedom that are dihedral angles, this value
								|specifies the half-width (ie radius) of the interval of allowed angles.
							""".trimMargin())

							if (sliderFloat("##dihedralRadius", pDihedralRadiusDegrees, 0f, 180f, "%.1f")) {
								fragInfo.motionSettings.dihedralRadiusDegrees = pDihedralRadiusDegrees.value.toDouble()
								resetMotionInfos()
							}
							sameLine()
							infoTip("Ctrl-click to type a precise value")

							spacing()

							if (checkbox("Include H-group rotations", pIncludeHGroupRotations)) {
								fragInfo.motionSettings.includeHGroupRotations = pIncludeHGroupRotations.value
								resetMotionInfos()
							}
						}
					}
				}
				column(300f) { // motions column

					text("Choose a conformation to test:")
					child("testConfs", 280f, 140f, true) {
						val fragInfo = selectedFragInfo
						if (fragInfo != null) {

							for (confInfo in fragInfo.confInfos) {

								// only show selected confs
								if (confInfo.conf !in fragInfo.confs) {
									continue
								}

								if (radioButton("${confInfo.conf.name}##confRadio-${confInfo.id}", selectedConfInfo === confInfo)) {
									selectConf(view, fragInfo, confInfo)
								}
							}
						}
					}

					spacing()

					text("Degrees of Freedom:")
					indent(20f)

					if (motionInfos.isNotEmpty()) {

						for (motionInfo in motionInfos) {

							// breathe a little
							spacing()
							spacing()
							separator()
							spacing()
							spacing()

							text(motionInfo.label)
							motionInfo.gui(imgui, view)
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
							motionInfos.forEach { it.jiggle(rand) }
							view.moleculeChanged()
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

	inner class MolInfo(val mol: Molecule, val molType: MoleculeType) {

		val posInfos = ArrayList<PosInfo>()
		var numConfs: BigInteger = BigInteger.ZERO

		fun updateCounts() {
			numConfs = BigInteger.ONE
			for (posInfo in posInfos) {
				numConfs *= posInfo.confSpace.numConfs().toBigInteger()
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

				// draw the window
				window("Flexibility Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

					// TODO: show each molecule GUI in a separate tab? or column?

					for ((i, molInfo) in molInfos.values.withIndex()) {
						withId(i) {

							if (i > 0) {
								// let the entries breathe
								spacing()
								spacing()
								separator()
								spacing()
								spacing()
							}

							text("${molInfo.mol}")
							indent(20f)

							text("Mutable Positions:")
							spacing()
							columns(2)
							indent(20f)
							val mutInfos = molInfo.posInfos.filter { it.isMutable }
							for (posInfo in mutInfos) {

								selectable(posInfo.pos.name, posInfo.pSelected)
								nextColumn()
								text("${posInfo.confSpace.numConfs()} confs")
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
									text("${flexInfo.confSpace.numConfs()} confs")
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

							unindent(20f)
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

				// cleanup infos
				molInfos.clear()
			}
		)
	}
}


private sealed class MotionInfo {

	abstract val label: String
	abstract fun gui(imgui: Commands, view: MoleculeRenderView)
	abstract fun jiggle(rand: Random)

	class DihedralInfo(
		val id: Int,
		pos: DesignPosition,
		motion: ConfLib.ContinuousMotion.DihedralAngle,
		val initialDegrees: Double,
		val radiusDegrees: Double
	) : MotionInfo() {

		inner class AngleInfo(pos: DesignPosition, motion: ConfLib.ContinuousMotion.DihedralAngle) {

			val dihedral = pos.dihedralAngle(motion)
				.apply {
					setDegrees(initialDegrees)
				}

			val minValue = (initialDegrees - radiusDegrees).toFloat()
			val maxValue = (initialDegrees + radiusDegrees).toFloat()

			val label = "Dihedral Angle: ${dihedral.a.name}, ${dihedral.b.name}, ${dihedral.c.name}, ${dihedral.d.name}"

			val pValue = Ref.of(initialDegrees.toFloat())
		}

		val angleInfo =
			if (pos.supportsDihedralAngle(motion)) {
				AngleInfo(pos, motion)
			} else {
				null
			}

		override val label = angleInfo?.label ?: "Dihedral Angle: (Incompatible)"

		override fun gui(imgui: Commands, view: MoleculeRenderView): Unit = imgui.run {
			angleInfo?.run {
				text("Range: %.1f to %.1f".format(minValue, maxValue))
				if (sliderFloat("Angle##motion-$id", pValue, minValue, maxValue, format = "%.1f")) {
					dihedral.setDegrees(pValue.value.toDouble())
					view.moleculeChanged()
				}
			} ?: run {
				sameLine()
				infoTip("""
					|This dihedral angle degree of freedom has been defined by the fragment,
					|but for some reason, it cannot be matched to the atoms in this design
					|position. This is most likely an error in the definition of the fragment
					|and its degrees of freedom.
				""".trimMargin())
			}
		}

		override fun jiggle(rand: Random) {
			angleInfo?.run {
				pValue.value = if (minValue != maxValue) {
					rand.nextDouble(minValue.toDouble(), maxValue.toDouble()).toFloat()
				} else {
					minValue
				}
				dihedral.setDegrees(pValue.value.toDouble())
			}
		}
	}
}
