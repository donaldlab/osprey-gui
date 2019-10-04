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
import edu.duke.cs.ospreygui.prep.MutablePosition
import edu.duke.cs.ospreygui.prep.MoleculePrep
import java.nio.file.Paths


class MutablePositionsEditor(val prep: MoleculePrep) : SlideFeature {

	// TODO: add shortcuts for proteins

	override val id = FeatureId("design.mutations")

	private val winState = WindowState()
	private val alert = Alert()

	data class SelectedAtom(val atom: Atom, val label: String, val view: MoleculeRenderView) {

		val pSelected = Ref.of(false)

		fun addEffect() {
			view.renderEffects[atom] = selectedEffect
		}

		fun removeEffect() {
			view.renderEffects.remove(atom)
		}
	}

	data class AnchorAtom(val atom: Atom, val label: String, val view: MoleculeRenderView) {

		fun addEffect() {
			view.renderEffects[atom] = anchorEffect
		}

		fun removeEffect() {
			view.renderEffects.remove(atom)
		}
	}

	private inner class PositionEditor(val pos: MutablePosition) {

		val mol  = pos.mol

		val winState = WindowState()
			.apply { pOpen.value = true }
		val nameBuf = Commands.TextBuffer(1024)

		val clickTracker = ClickTracker()

		val selectedAtoms = ArrayList<SelectedAtom>()
		val anchorAtoms = ArrayList<AnchorAtom>()

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
					for (atom in pos.removalAtoms) {
						val info = SelectedAtom(atom, atom.label(), view)
						selectedAtoms.add(info)
						info.addEffect()
					}
					for (atom in pos.anchorAtoms) {
						val info = AnchorAtom(atom, atom.label(), view)
						anchorAtoms.add(info)
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
										toggleAtom(atom, view)
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

					// show replace-able atoms
					text("Atoms to replace: ${selectedAtoms.size}")
					beginChild("selectedAtoms", 300f, 200f, true)
					for (info in selectedAtoms) {
						selectable(info.label, info.pSelected)
					}
					endChild()

					styleEnabledIf(selectedAtoms.any { it.pSelected.value }) {
						if (button("Remove")) {
							selectedAtoms
								.filter { it.pSelected.value }
								.forEach {
									toggleAtom(it.atom, it.view)
								}
						}
					}

					spacing()

					// show anchor atoms
					text("Bonded Atoms: ${anchorAtoms.size}")
					beginChild("anchorAtoms", 300f, 100f, true)
					for (info in anchorAtoms) {
						selectable(info.label, false)
					}
					endChild()

					end()
				},
				onClose = {
					positionEditor = null

					// remove the effects
					slidewin.hoverEffects.remove(id)
					selectedAtoms.forEach { it.removeEffect() }
					anchorAtoms.forEach { it.removeEffect() }
				}
			)
		}

		private fun toggleAtom(atom: Atom, view: MoleculeRenderView) {

			// find the existing selected atom, if any
			var info = selectedAtoms.find { it.atom == atom }
			if (info != null) {

				// atom already selected, so unselect it
				selectedAtoms.remove(info)
				info.removeEffect()

				// update the design pos too
				pos.removalAtoms.remove(info.atom)

			} else {

				// atom not selected yet

				// remove any existing anchors
				anchorAtoms
					.find { it.atom == atom }
					?.let {
						it.removeEffect()
						anchorAtoms.remove(it)
					}

				// atom not selected yet, so select it
				info = SelectedAtom(atom, atom.label(), view)
				selectedAtoms.add(info)
				info.addEffect()

				// update the design pos too
				pos.removalAtoms.add(info.atom)
			}

			val selectedAtoms = selectedAtoms
				.map { it.atom }
				.toCollection(Atom.setIdentity())

			// clear existing anchors
			anchorAtoms.forEach { it.removeEffect() }
			anchorAtoms.clear()
			pos.anchorAtoms.clear()

			// update anchor atoms
			for (selectedInfo in this.selectedAtoms) {
				mol.bonds.bondedAtoms(selectedInfo.atom)
					.filter { it !in selectedAtoms }
					.forEach {
						val anchor = AnchorAtom(it, it.label(), selectedInfo.view)
						anchorAtoms.add(anchor)
						anchor.addEffect()
						pos.anchorAtoms.add(it)
					}
			}
		}
	}
	private var positionEditor: PositionEditor? = null

	private data class PosInfo(val pos: MutablePosition) {

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
		if (menuItem("Mutations")) {
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
							addLib(imgui, OspreyGui.getResourceAsString("conflib/lovell.conflib.toml"))
						}
						conflibTooltip(null, info.description, info.citation)
					}
					endPopup()
				}

				sameLine()

				if (button("Add from file")) {
					addLibFromFile(this)
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
		val pos = MutablePosition("$prefix$num", mol)
		positions.add(pos)

		posInfosByMol.getValue(mol).add(PosInfo(pos))

		// start the position editor
		positionEditor = PositionEditor(pos)
	}

	private var libDir = Paths.get("").toAbsolutePath()
	private val conflibFilter = FilterList(listOf("conflib.toml"))

	private fun addLibFromFile(imgui: Commands) {
		FileDialog.openFiles(
			conflibFilter,
			defaultPath = libDir
		)?.let { paths ->
			paths.firstOrNull()?.parent?.let { libDir = it }
			for (path in paths) {
				addLib(imgui, path.read())
			}
		}
	}

	private fun addLib(imgui: Commands, toml: String) {
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
