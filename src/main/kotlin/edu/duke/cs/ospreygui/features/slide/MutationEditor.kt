package edu.duke.cs.ospreygui.features.slide

import cuchaz.kludge.imgui.Commands
import cuchaz.kludge.tools.ByteFlags
import cuchaz.kludge.tools.IntFlags
import cuchaz.kludge.tools.Ref
import cuchaz.kludge.window.FileDialog
import cuchaz.kludge.window.FilterList
import edu.duke.cs.molscope.Slide
import edu.duke.cs.molscope.gui.*
import edu.duke.cs.molscope.gui.features.FeatureId
import edu.duke.cs.molscope.gui.features.WindowState
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.molecule.Molecule
import edu.duke.cs.molscope.molecule.Polymer
import edu.duke.cs.molscope.render.RenderEffect
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.OspreyGui
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.io.read
import edu.duke.cs.ospreygui.prep.DesignPosition
import edu.duke.cs.ospreygui.prep.MoleculePrep
import java.nio.file.Paths


class DesignPositionsEditor(val prep: MoleculePrep) : SlideFeature {

	// TODO: add shortcuts for proteins

	override val id = FeatureId("design.positions")

	private val winState = WindowState()
	private val alert = Alert()

	data class AnchorAtom(val atom: Atom, val label: String, val view: MoleculeRenderView) {

		val pSelected = Ref.of(false)

		fun addEffect() {
			view.renderEffects[atom] = anchorEffect
		}

		fun removeEffect() {
			view.renderEffects.remove(atom)
		}
	}

	data class SelectedAtom(val atom: Atom, val label: String, val view: MoleculeRenderView) {

		val pSelected = Ref.of(false)

		fun addEffect() {
			view.renderEffects[atom] = selectedEffect
		}

		fun removeEffect() {
			view.renderEffects.remove(atom)
		}
	}

	enum class PickMode {
		Anchor,
		Sidechain
	}

	private inner class PositionEditor(val pos: DesignPosition) {

		val mol  = pos.mol

		val winState = WindowState()
			.apply { pOpen.value = true }
		val nameBuf = Commands.TextBuffer(1024)

		val clickTracker = ClickTracker()
		var pickMode = PickMode.Anchor

		val anchorAtoms = ArrayList<AnchorAtom>()
		val sidechainAtoms = ArrayList<SelectedAtom>()

		fun Atom.label(): String {

			// use the atom name as the label
			var label = name

			// if there's a residue, append that too
			if (mol is Polymer) {
				val res = mol.findResidue(this)
				if (res != null) {
					label += " @ $res"
				}
			}

			return label
		}

		fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
			winState.render(
				onOpen = {

					// add the hover effect
					slidewin.hoverEffects[id] = hoverEffect

					// find the view for this molecule
					val view = slide.views
						.filterIsInstance<MoleculeRenderView>()
						.find { it.mol == mol }
					?: throw Error("can't init design position, molecule has no render view")

					// init the atoms lists
					for (atom in pos.anchorAtoms) {
						val info = AnchorAtom(atom, atom.label(), view)
						anchorAtoms.add(info)
						info.addEffect()
					}
					for (atom in pos.atoms) {
						val info = SelectedAtom(atom, atom.label(), view)
						sidechainAtoms.add(info)
						info.addEffect()
					}
				},
				whenOpen = {

					// did we click anything?
					if (clickTracker.clicked(slidewin)) {

						// select the atom from the click, if any
						slidewin.mouseTarget?.let { target ->
							(target.view as? MoleculeRenderView)?.let { view ->

								// make sure we're picking from the same molecule
								if (view.mol == mol) {

									(target.target as? Atom)?.let { atom ->

										// add/remove the atom to/from the selection
										when (pickMode) {
											PickMode.Anchor -> toggleAnchorAtom(atom, view)
											PickMode.Sidechain -> toggleSidechainAtom(atom, view)
										}
									}
								}
							}
						}
					}

					// draw the window
					begin("Design Position Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize))

					// edit the name
					nameBuf.text = pos.name
					inputText("Name", nameBuf)
					pos.name = nameBuf.text

					spacing()

					// show anchor atoms
					if (radioButton("Anchor Atoms: ${anchorAtoms.size}", pickMode == PickMode.Anchor)) {
						pickMode = PickMode.Anchor
					}
					sameLine()
					infoTip("""
						|Anchor atoms help align the sidechain to the mainchain for a design position.
						|At least three anchor atoms must be selected.
						|Sidechain conformations are aligned such that the anchor 1 positions are exactly conincident.
						|Then, the anchor 1-2 lines are made parallel.
						|Then, the anchor 1-2-3 planes are made parallel.
						""".trimMargin())
					beginChild("anchorAtoms", 300f, 100f, true)

					fun showAnchor(id: Int, anchorAtom: AnchorAtom?) {
						if (anchorAtom != null) {
							selectable("anchor $id: ${anchorAtom.label}", anchorAtom.pSelected)
						} else {
							selectable("anchor $id: none", false)
						}
					}
					showAnchor(1, anchorAtoms.getOrNull(0))
					showAnchor(2, anchorAtoms.getOrNull(1))
					showAnchor(3, anchorAtoms.getOrNull(2))
					for (i in 3 until anchorAtoms.size) {
						val anchorAtom = anchorAtoms[i]
						selectable(anchorAtom.label, anchorAtom.pSelected)
					}
					endChild()

					styleEnabledIf(anchorAtoms.any { it.pSelected.value }) {
						if (button("Remove")) {
							anchorAtoms
								.filter { it.pSelected.value }
								.forEach {
									toggleAnchorAtom(it.atom, it.view)
								}
						}
					}

					spacing()

					// show sidechain atoms
					if (radioButton("Sidechain Atoms: ${sidechainAtoms.size}", pickMode == PickMode.Sidechain)) {
						pickMode = PickMode.Sidechain
					}
					beginChild("sidechainAtoms", 300f, 200f, true)
					for (info in sidechainAtoms) {
						selectable(info.label, info.pSelected)
					}
					endChild()

					styleEnabledIf(sidechainAtoms.any { it.pSelected.value }) {
						if (button("Remove")) {
							sidechainAtoms
								.filter { it.pSelected.value }
								.forEach {
									toggleSidechainAtom(it.atom, it.view)
								}
						}
					}

					end()
				},
				onClose = {
					positionEditor = null

					// remove the effects
					slidewin.hoverEffects.remove(id)
					anchorAtoms.forEach { it.removeEffect() }
					sidechainAtoms.forEach { it.removeEffect() }
				}
			)
		}

		private fun toggleAnchorAtom(atom: Atom, view: MoleculeRenderView) {

			// find the existing selected atom, if any
			var info = anchorAtoms.find { it.atom == atom }
			if (info != null) {

				// atom already selected, so unselect it
				anchorAtoms.remove(info)
				info.removeEffect()

				// update the design pos too
				pos.anchorAtoms.remove(info.atom)

			} else {

				// atom not selected yet, so select it
				info = AnchorAtom(atom, atom.label(), view)
				anchorAtoms.add(info)
				info.addEffect()

				// update the design pos too
				pos.anchorAtoms.add(info.atom)
			}
		}

		private fun toggleSidechainAtom(atom: Atom, view: MoleculeRenderView) {

			// find the existing selected atom, if any
			var info = sidechainAtoms.find { it.atom == atom }
			if (info != null) {

				// atom already selected, so unselect it
				sidechainAtoms.remove(info)
				info.removeEffect()

				// update the design pos too
				pos.atoms.remove(info.atom)

			} else {

				// atom not selected yet, so select it
				info = SelectedAtom(atom, atom.label(), view)
				sidechainAtoms.add(info)
				info.addEffect()

				// update the design pos too
				pos.atoms.add(info.atom)
			}
		}
	}
	private var positionEditor: PositionEditor? = null

	private data class PosInfo(val pos: DesignPosition) {

		val pSelected = Ref.of(false)
	}

	private val posInfosByMol = HashMap<Molecule,ArrayList<PosInfo>>()

	// cache the built-in conflib metadata
	private val builtInConflibPaths = listOf(
		"conflib/lovell.conflib.toml"
	)
	data class ConfLibInfo(
		val name: String,
		val description: String?,
		val citation: String?
	)
	private val conflibInfos = ArrayList<ConfLibInfo>().apply {
		for (path in builtInConflibPaths) {
			val conflib = ConfLib.from(OspreyGui.getResourceAsString(path))
			add(ConfLibInfo(
				conflib.name,
				conflib.description,
				conflib.citation
			))
		}
	}

	override fun menu(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
		if (menuItem("Positions")) {
			winState.isOpen = true
		}
	}

	override fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

		winState.render(
			onOpen = {

				// reset pos infos
				posInfosByMol.clear()
				for (mol in prep.getIncludedMols()) {
					val infos = posInfosByMol.getOrPut(mol) { ArrayList() }
					prep.mutablePositionsByMol.get(mol)?.forEach { pos ->
						infos.add(PosInfo(pos))
					}
				}
			},
			whenOpen = {

				// draw the window
				begin("Mutation Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize))

				fun conflibTooltip(name: String?, desc: String?, citation: String?) {
					if (isItemHovered()) {
						beginTooltip()
						if (name != null) {
							text(name)
						}
						if (desc != null) {
							text(desc)
						}
						if (citation != null) {
							text(citation)
						}
						endTooltip()
					}
				}

				// show available libraries
				text("Conformation Libraries")
				beginChild("libs", 300f, 100f, true)
				for (conflib in prep.conflibs) {
					text(conflib.name)
					conflibTooltip(conflib.name, conflib.description, conflib.citation)
				}
				endChild()

				if (button("Add")) {
					openPopup("addlib")
				}
				if (beginPopup("addlib")) {
					for (info in conflibInfos) {
						if (menuItem(info.name)) {
							addLib(OspreyGui.getResourceAsString("conflib/lovell.conflib.toml"))
						}
						conflibTooltip(null, info.description, info.citation)
					}
					endPopup()
				}

				sameLine()

				if (button("Add from file")) {
					addLibFromFile()
				}

				for ((i, mol) in prep.getIncludedMols().withIndex()) {

					if (i > 0) {
						// let the entries breathe
						spacing()
						spacing()
						separator()
						spacing()
						spacing()
					}

					// show the current design positions
					val posInfos = posInfosByMol.getValue(mol)

					text("$mol: ${posInfos.size} positions(s)")
					beginChild("positions-${System.identityHashCode(mol)}", 300f, 200f, true)
					for (posInfo in posInfos) {
						selectable(posInfo.pos.name, posInfo.pSelected)
					}
					endChild()

					if (button("Add")) {
						makeNewPosition(mol)
					}

					sameLine()

					val canEdit = posInfos.count { it.pSelected.value } == 1
					styleEnabledIf(canEdit) {
						if (button("Edit") && canEdit) {
							posInfos
								.find { it.pSelected.value }
								?.let {
									positionEditor = PositionEditor(it.pos)
								}
						}
					}

					sameLine()

					styleEnabledIf(posInfos.any { it.pSelected.value }) {
						if (button("Remove")) {
							posInfos
								.filter { it.pSelected.value }
								.forEach {
									posInfos.remove(it)
									prep.mutablePositionsByMol[mol]?.remove(it.pos)
								}
						}
					}
				}

				// render the position editor, when active
				positionEditor?.gui(imgui, slide, slidewin)

				alert.render(this)

				end()
			},
			onClose = {

				// cleanup
				posInfosByMol.clear()
			}
		)
	}

	private fun makeNewPosition(mol: Molecule) {

		val positions = prep.mutablePositionsByMol
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
		val pos = DesignPosition("$prefix$num", mol)
		positions.add(pos)

		posInfosByMol.getValue(mol).add(PosInfo(pos))

		// start the position editor
		positionEditor = PositionEditor(pos)
	}

	private var libDir = Paths.get("").toAbsolutePath()
	private val conflibFilter = FilterList(listOf("conflib.toml"))

	private fun addLibFromFile() {
		FileDialog.openFiles(
			conflibFilter,
			defaultPath = libDir
		)?.let { paths ->
			paths.firstOrNull()?.parent?.let { libDir = it }
			for (path in paths) {
				addLib(path.read())
			}
		}
	}

	private fun addLib(toml: String) {
		try {
			prep.conflibs.add(toml)
		} catch (ex: MoleculePrep.DuplicateConfLibException) {
			alert.show("Skipped adding duplicate Conformation Library:\n${ex.conflib.name}")
		}
	}
}

private val hoverEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset, RenderEffect.Flags.Outset),
	200u, 200u, 200u
)
private val selectedEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset, RenderEffect.Flags.Outset),
	255u, 255u, 255u
)
private val anchorEffect = RenderEffect(
	ByteFlags.of(RenderEffect.Flags.Highlight, RenderEffect.Flags.Inset, RenderEffect.Flags.Outset),
	0u, 255u, 0u
)
