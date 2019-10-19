package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.gui.styleEnabledIf
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.features.components.DesignPositionEditor
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.prep.ConfSpacePrep
import edu.duke.cs.ospreygui.prep.DesignPosition
import java.math.BigInteger
import java.text.NumberFormat
import java.util.*
import kotlin.collections.ArrayList


class ConformationEditor(val prep: ConfSpacePrep) : SlideFeature {

	override val id = FeatureId("edit.conformations")

	private inner class ConfEditor(val molInfo: MolInfo, val posInfo: PosInfo) {

		val winState = WindowState()
			.apply { pOpen.value = true }
		val posEditor = DesignPositionEditor(prep, posInfo.pos, molInfo.molType)
		var resetTabSelection = true
		val confsTabState = Commands.TabState()

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
					begin("Design Position Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize))

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

						tabItem(confsTabState, "Conformations", flags = makeFlags(),
							onActivated = {
								activateConfsTab()
							},
							whenActive = {
								renderConfsTab(imgui, slidewin, view)
							},
							onDeactivated = {
								deactivateConfsTab(view)
							}
						)
					}

					end()
				},
				onClose = {

					// cleanup the pos editor
					posEditor.closed()
					confEditor = null
				}
			)
		}

		fun activateConfsTab() {
			// TODO
		}

		fun renderConfsTab(imgui: Commands, slidewin: SlideCommands, view: MoleculeRenderView) = imgui.run {
			// TODO
		}

		fun deactivateConfsTab(view: MoleculeRenderView) {
			// TODO
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

			val positions = prep.designPositionsByMol
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

		val confSpace = prep.positionConfSpaces.getOrMake(pos)
		val isMutable = confSpace.isMutable()
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
				for ((moltype, mol) in prep.mols) {
					molInfos.getOrPut(mol) { MolInfo(mol, moltype) }.apply {
						prep.designPositionsByMol[mol]?.forEach { pos ->
							posInfos.add(PosInfo(pos))
						}
					}
				}

				updateCounts()
			},
			whenOpen = {

				// draw the window
				begin("Flexibility Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize))

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
						for (posInfo in molInfo.posInfos.filter { it.isMutable }) {

							selectable(posInfo.pos.name, posInfo.pSelected)
							nextColumn()
							text("${posInfo.confSpace.numConfs()} confs")
							nextColumn()
						}
						columns(1)
						unindent(20f)
						spacing()

						text("Flexible Positions:")
						beginChild("flexpos", 300f, 200f, true)
						columns(2)
						for (flexInfo in molInfo.posInfos.filter { it.isFlexible }) {

							selectable(flexInfo.pos.name, flexInfo.pSelected)
							nextColumn()
							text("${flexInfo.confSpace.numConfs()} confs")
							nextColumn()
						}
						columns(1)
						endChild()

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
										prep.designPositionsByMol[molInfo.mol]?.remove(it.pos)
										prep.positionConfSpaces.remove(it.pos)
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

				end()
			},
			onClose = {

				// cleanup infos
				molInfos.clear()
			}
		)
	}
}
