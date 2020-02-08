package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.ByteFlags
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.*
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.molecule.*
import edu.duke.cs.molscope.render.HoverEffects
import edu.duke.cs.molscope.render.RenderEffect
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.features.components.ConfLibPicker
import edu.duke.cs.ospreygui.features.components.DesignPositionEditor
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.prep.ConfSpacePrep
import edu.duke.cs.ospreygui.prep.DesignPosition
import java.math.BigInteger
import java.text.NumberFormat
import kotlin.collections.ArrayList


class MutationEditor(val prep: ConfSpacePrep) : SlideFeature {

	override val id = FeatureId("edit.mutations")

	private val winState = WindowState()

	private inner class MutEditor(val posInfo: PosInfo) {

		val pos get() = posInfo.pos
		val moltype get() = posInfo.moltype
		val mol= pos.mol

		val winState = WindowState()
			.apply { pOpen.value = true }

		val posEditor = DesignPositionEditor(prep, pos)
		val mutationsTabState = Commands.TabState()
		var resetTabSelection = true
		var hoverEffects = null as HoverEffects.Writer?

		fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

			val view = slide.views
				.filterIsInstance<MoleculeRenderView>()
				.find { it.mol == mol }
				?: throw Error("can't init design position, molecule has no render view")

			winState.render(
				onOpen = {

					// add the hover effect
					hoverEffects = slidewin.hoverEffects.writer().apply {
						effect = hoverEffect
					}

					// init the pos editor
					posEditor.init(view)

					updateSequenceCounts()
				},
				whenOpen = {

					// draw the window
					window("Edit ${posInfo.pos.name} mutations##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

						// if we need to reset the tab selection, make the flags for the first tab
						fun makeFlags() =
							if (resetTabSelection) {
								resetTabSelection = false
								IntFlags.of(Commands.TabItemFlags.SetSelected)
							} else {
								IntFlags.of(Commands.TabItemFlags.None)
							}

						tabBar("tabs") {
							when (moltype) {
								MoleculeType.Protein -> tabItem("Protein", flags = makeFlags()) {
									posEditor.guiProtein(imgui, slidewin, view)
								}
								// TODO: others?
								else -> Unit
							}
							tabItem("Atoms", flags = makeFlags()) {
								posEditor.guiAtoms(imgui, slidewin, view)
							}
							tabItem(mutationsTabState, "Mutations",
								onActivated = {
									activateMutationsTab()
								},
								whenActive = {
									renderMutationsTab(imgui, view)
								},
								onDeactivated = {
									deactivateMutationsTab(view)
								}
							)
						}
					}
				},
				onClose = {

					// cleanup effects
					hoverEffects?.close()
					hoverEffects = null

					// deactivate the mutations tab if it's open
					if (mutationsTabState.wasActive) {
						mutationsTabState.wasActive = false
						deactivateMutationsTab(view)
					}

					// cleanup the pos editor
					posEditor.closed()
					mutEditor = null
				}
			)
		}

		private val conflibPicker = ConfLibPicker(prep).apply {
			onAdd = { activateMutationsTab() }
		}

		inner class SeqInfo(
			val type: String,
			val label: String,
			val frag: ConfLib.Fragment
		) {
			val pSelected = Ref.of(false)

			// pick an arbitrary conformation from the fragment to show in the GUI
			val conf = frag.confs.values.first()
		}

		private val seqInfos = ArrayList<SeqInfo>()
		private var selectedSeqInfo: SeqInfo? = null

		private fun activateMutationsTab() {

			fun add(type: String, isWildtype: Boolean, frag: ConfLib.Fragment): SeqInfo {

				val label = if (isWildtype) {
					"WildType: $type"
				} else {
					type
				}

				val seqInfo = SeqInfo(type, label, frag).apply {

					// is this fragment selected?
					pSelected.value = pos.confSpace.mutations.contains(type)
				}

				seqInfos.add(seqInfo)
				return seqInfo
			}

			// rebuild the sequence infos
			seqInfos.clear()

			// add the wild type first, if we can
			val wildTypeFrag = pos.confSpace.wildTypeFragment
			val wildTypeInfo = if (wildTypeFrag != null) {
				add(wildTypeFrag.type, true, wildTypeFrag)
			} else {
				null
			}

			// select the wildtype info by default
			selectedSeqInfo = wildTypeInfo

			// add fragments from the libraries
			for (conflib in prep.conflibs) {
				conflib.fragments.values
					.filter { pos.isFragmentCompatible(it) }
					.groupBy { it.type }
					.toMutableMap()
					.apply {
						// remove the wildtype if we already have it
						wildTypeFrag?.type?.let { remove(it) }
					}
					.toList()
					.sortedBy { (type, _) -> type }
					.forEach { (type, frag) ->
						add(type, false, frag.first())
					}
			}

			// TODO: collect tags for the fragments? eg, hydrophobic, aromatic
		}

		private fun renderMutationsTab(imgui: Commands, view: MoleculeRenderView) = imgui.run {

			// show the conflib picker
			conflibPicker.render(imgui)

			// show the available mutations
			text("Mutations:")
			sameLine()
			infoTip("""
				|Select the mutations you want to include in your design by clicking the checkboxes.
				|You can temporarily preview a mutation by selecting the radion button next to a mutation.
				|All temporary mutations will be reverted when you're finished with the mutation editor.
			""".trimMargin())
			child("mutations", 300f, 400f, true) {
				if (seqInfos.isNotEmpty()) {
					for (info in seqInfos) {
						if (radioButton("##radio-${info.type}", selectedSeqInfo == info)) {
							selectedSeqInfo = info
							setConf(view, info.frag, info.conf)
						}
						sameLine()
						if (checkbox("${info.label}##check-${info.type}", info.pSelected)) {

							// mark the mutation as included or not in the design position conf space
							if (info.pSelected.value) {
								pos.confSpace.mutations.add(info.type)
							} else {
								pos.confSpace.mutations.remove(info.type)

								// also, remove the confs for this mutation, if any
								pos.confSpace.confs.removeByFragmentType(info.type)
							}

							// update the sequence count
							updateSequenceCounts()
						}
					}
				} else {
					text("(no compatible mutations)")
				}
			}
		}

		private fun deactivateMutationsTab(view: MoleculeRenderView) {

			// restore the wildtype if needed
			pos.confSpace.wildTypeFragment?.let { setConf(view, it, it.confs.values.first()) }

			selectedSeqInfo = null
		}

		private fun setConf(view: MoleculeRenderView, frag: ConfLib.Fragment, conf: ConfLib.Conf) {

			pos.setConf(frag, conf)

			posEditor.resetInfos()
			view.moleculeChanged()
		}
	}
	private var mutEditor: MutEditor? = null

	private inner class PosInfo(val pos: DesignPosition, val moltype: MoleculeType) {

		val label = pos.name
	}

	private val DesignPosition.confSpace get() = prep.confSpace.positionConfSpaces.getOrMake(this)

	private inner class MolInfo(val molType: MoleculeType, val mol: Molecule) {

		val label = mol.toString()
		val posInfos = ArrayList<PosInfo>()
		var numSequences = BigInteger.ZERO

		fun updateSequenceCount() {
			if (posInfos.isEmpty()) {
				numSequences = BigInteger.ZERO
			} else {
				numSequences = BigInteger.ONE
				for (info in posInfos) {
					numSequences *= info.pos.confSpace.mutations.size.toBigInteger()
				}
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

			val posInfo = PosInfo(pos, molType)
			posInfos.add(posInfo)
			return posInfo
		}
	}
	private val molInfos = ArrayList<MolInfo>()

	private var selectedPosInfo = null as PosInfo?

	private val sequenceFormatter = NumberFormat.getIntegerInstance()
		.apply {
			isGroupingUsed = true
		}

	private var numSequences = BigInteger.ZERO

	private fun updateSequenceCounts() {
		molInfos.forEach { it.updateSequenceCount() }
		numSequences = molInfos
			.takeIf { it.isNotEmpty() }
			?.map { it.numSequences }
			?.reduce { a, b -> a*b }
			?: BigInteger.ZERO
	}

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Mutations")) {
			winState.isOpen = true
		}
	}

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		winState.render(
			onOpen = {

				// reset infos
				molInfos.clear()
				for ((moltype, mol) in prep.confSpace.mols) {
					MolInfo(moltype, mol).apply {
						molInfos.add(this)
						prep.confSpace.designPositionsByMol[mol]?.forEach { pos ->
							posInfos.add(PosInfo(pos, moltype))
						}
					}
				}

				updateSequenceCounts()
			},
			whenOpen = {

				// draw the window
				window("Mutation Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize)) {

					tabBar("tabs") {

						for (molInfo in molInfos) {
							tabItem(molInfo.label) {

								text("${molInfo.posInfos.size} positions(s)")
								child("positions", 300f, 200f, true) {
									for (posInfo in molInfo.posInfos) {
										if (selectable(posInfo.label, selectedPosInfo === posInfo)) {
											selectedPosInfo = posInfo
										}
									}
								}

								if (button("Add")) {

									// start the position editor
									mutEditor = MutEditor(molInfo.makeNewPosition())
								}

								sameLine()

								val selectedPosInfo = selectedPosInfo

								val canEdit = selectedPosInfo != null
								styleEnabledIf(canEdit) {
									if (button("Edit") && canEdit) {

										// sadly the compiler isn't quite smart enough to figure out this can't be null
										// so put in a runtime check to make flow typing work correctly
										selectedPosInfo!!

										// start the position editor
										mutEditor = MutEditor(selectedPosInfo)
									}
								}

								sameLine()

								val canRemove = selectedPosInfo != null
								styleEnabledIf(canRemove) {
									if (button("Remove") && canRemove) {

										// sadly the compiler isn't quite smart enough to figure out this can't be null
										// so put in a runtime check to make flow typing work correctly
										selectedPosInfo!!

										molInfo.posInfos.remove(selectedPosInfo)
										prep.confSpace.designPositionsByMol[molInfo.mol]?.remove(selectedPosInfo.pos)
										prep.confSpace.positionConfSpaces.remove(selectedPosInfo.pos)
										updateSequenceCounts()
									}
								}

								spacing()

								// show the number of sequences so far
								text("Sequences: ${sequenceFormatter.format(molInfo.numSequences)}")
							}
						}
					}

					// for multiple molecules, show the combined sequence count
					if (molInfos.size > 1) {

						spacing()

						text("Combined Sequences: ${sequenceFormatter.format(numSequences)}")
					}

					// render the position editor, when active
					mutEditor?.gui(imgui, slide, slidewin)
				}
			},
			onClose = {

				// cleanup
				molInfos.clear()
			}
		)
	}
}

private val hoverEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset, RenderEffect.Flags.Outset),
	200u, 200u, 200u
)
