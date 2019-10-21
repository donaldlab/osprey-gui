package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.SlideCommands
import edu.duke.cs.molscope.gui.SlideFeature
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.gui.infoTip
import edu.duke.cs.molscope.gui.styleEnabledIf
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.tools.identityHashSet
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.features.components.ConfLibPicker
import edu.duke.cs.ospreygui.features.components.DesignPositionEditor
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.io.confRuntimeId
import edu.duke.cs.ospreygui.io.fragRuntimeId
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
		val posEditor = DesignPositionEditor(prep, posInfo.pos)
		var resetTabSelection = true
		val confsTabState = Commands.TabState()
		val conflibPicker = ConfLibPicker(prep).apply {
			onAdd = { resetInfos() }
		}

		inner class MutInfo(val type: String) {

			inner class FragInfo(val conflib: ConfLib?, val frag: ConfLib.Fragment) {

				val id = conflib.fragRuntimeId(frag)
				val confs = posInfo.confSpace.confs.getOrPut(frag) { identityHashSet() }

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

				val label = "${frag.name}, $numSelected/${confs.size} confs###$id"
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
			val numConfs = fragInfos.sumBy { it.confs.size }

			val label = "$type, $numSelected/$numConfs confs###$type"
		}
		val mutInfos = ArrayList<MutInfo>()
		var selectedConf: ConfLib.Conf? = null

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

		fun resetInfos() {
			mutInfos.clear()
			for (mut in posInfo.confSpace.mutations) {
				mutInfos.add(MutInfo(mut))
			}
		}

		fun activateConfsTab() {

			resetInfos()

			// find the wildtype conf info, if possible, and select it by default
			selectedConf = posInfo.confSpace.wildTypeFragment
				?.confs
				?.values
				?.firstOrNull()
		}

		fun renderConfsTab(imgui: Commands, slidewin: SlideCommands, view: MoleculeRenderView) = imgui.run {

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
			beginChild("confs", 300f, 400f, true)
			for (mutInfo in mutInfos) {
				treeNode(mutInfo.label) {
					for (fragInfo in mutInfo.fragInfos) {
						treeNode(fragInfo.label) {
							for (confInfo in fragInfo.confInfos) {

								if (radioButton("##radio-${confInfo.id}", selectedConf === confInfo.conf)) {
									selectedConf = confInfo.conf
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
			endChild()

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

		fun deactivateConfsTab(view: MoleculeRenderView) {

			// restore the wildtype if needed
			posInfo.confSpace.wildTypeFragment?.let { setConf(view, it, it.confs.values.first()) }
		}

		private fun setConf(view: MoleculeRenderView, frag: ConfLib.Fragment, conf: ConfLib.Conf) {

			posInfo.pos.setConf(frag, conf)

			// update the view
			posEditor.refresh(view)
			view.moleculeChanged()
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

		val confSpace get() = prep.positionConfSpaces.getOrMake(pos)
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
