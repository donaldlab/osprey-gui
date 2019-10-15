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
import edu.duke.cs.molscope.render.RenderEffect
import edu.duke.cs.molscope.view.MoleculeRenderView
import edu.duke.cs.ospreygui.features.components.ConfLibPicker
import edu.duke.cs.ospreygui.forcefield.amber.MoleculeType
import edu.duke.cs.ospreygui.io.ConfLib
import edu.duke.cs.ospreygui.prep.DesignPosition
import edu.duke.cs.ospreygui.prep.MoleculePrep
import edu.duke.cs.ospreygui.prep.Proteins
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


class MutationEditor(val prep: MoleculePrep) : SlideFeature {

	override val id = FeatureId("design.mutations")

	private val winState = WindowState()

	private inner class PositionEditor(val pos: DesignPosition, val moltype: MoleculeType) {

		val mol  = pos.mol

		val winState = WindowState()
			.apply { pOpen.value = true }
		val nameBuf = Commands.TextBuffer(1024)

		val clickTracker = ClickTracker()
		var autoSelectHydrogens = Ref.of(true)
		val mutationsTabState = Commands.TabState()

		inner class CurrentAtom(val atom: Atom, val label: String, val view: MoleculeRenderView) {

			val pSelected = Ref.of(false)

			fun addEffect() {
				view.renderEffects[atom] = selectedEffect
			}

			fun removeEffect() {
				view.renderEffects.remove(atom)
			}
		}

		private val currentAtoms = ArrayList<CurrentAtom>()

		private fun toggleCurrentAtom(atom: Atom, view: MoleculeRenderView) {

			// is the atom already selected?
			val isSelected = pos.currentAtoms.any { it === atom }
			if (isSelected) {

				// atom already selected, so deselect it
				pos.currentAtoms.removeIf { it === atom }

				// toggle the bonded hydrogens if needed
				if (autoSelectHydrogens.value) {
					val hAtoms = mol.bonds.bondedAtoms(atom)
						.filter { it.element == Element.Hydrogen }
						.toIdentitySet()
					pos.currentAtoms.removeIf { it in hAtoms }
				}

			} else {

				// atom not selected yet, so select it
				pos.currentAtoms.add(atom)

				// toggle the bonded hydrogens if needed
				if (autoSelectHydrogens.value) {
					mol.bonds.bondedAtoms(atom)
						.filter { it.element == Element.Hydrogen }
						.forEach { h -> pos.currentAtoms.add(h) }
				}
			}

			pos.resetConfSpace()
			resetInfos(view)
		}

		private fun removeCurrentAtoms(atoms: Set<Atom>, view: MoleculeRenderView) {

			pos.currentAtoms.removeIf { it in atoms }

			pos.resetConfSpace()
			resetInfos(view)
		}

		inner class AnchorAtom(val atom: Atom, val label: String, val view: MoleculeRenderView) {

			val pSelected = Ref.of(false)

			fun addEffect() {
				view.renderEffects[atom] = anchorEffect
			}

			fun removeEffect() {
				view.renderEffects.remove(atom)
			}

			var atomClickHandler: ((MoleculeRenderView, Atom) -> Unit)? = null
		}

		inner class AnchorInfo(
			val anchor: DesignPosition.Anchor
		) {

			val anchorAtoms = ArrayList<AnchorAtom>()

			fun findAtomInfo(atom: Atom) =
				anchorAtoms.find { it.atom === atom }

			fun removeEffects() {
				anchorAtoms.forEach { it.removeEffect() }
			}
		}

		inner class AnchorGroupInfo(
			val anchors: MutableList<DesignPosition.Anchor>
		) {

			val anchorInfos = ArrayList<AnchorInfo>()

			fun removeEffects() {
				anchorInfos.forEach { it.removeEffects() }
			}

			fun replaceAnchor(old: DesignPosition.Anchor, new: DesignPosition.Anchor) {
				anchors[anchors.indexOf(old)] = new
			}
		}

		val anchorGroupInfos = ArrayList<AnchorGroupInfo>()

		fun resetInfos(view: MoleculeRenderView) {

			anchorGroupInfos.forEach { it.removeEffects() }
			anchorGroupInfos.clear()
			currentAtoms.forEach { it.removeEffect() }
			currentAtoms.clear()

			// add the anchor effects
			for (anchorGroup in pos.anchorGroups) {
				anchorGroupInfos.add(AnchorGroupInfo(anchorGroup).apply {
					for (anchor in anchorGroup) {
						anchorInfos.add(AnchorInfo(anchor).apply {
							for (atom in anchor.anchorAtoms) {
								val info = AnchorAtom(atom, atom.label(), view)
								anchorAtoms.add(info)
								info.addEffect()
							}
						})
					}
				})
			}

			// add the current atoms effects
			for (atom in pos.currentAtoms) {
				val info = CurrentAtom(atom, atom.label(), view)
				currentAtoms.add(info)
				info.addEffect()
			}
		}

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

		val DesignPosition.confSpace get() = prep.positionConfSpaces.getOrMake(this)

		fun DesignPosition.resetConfSpace() {

			// delete the old conf space
			prep.positionConfSpaces.remove(pos)

			// make a new conf space
			confSpace.apply {

				// make a new wildtype fragment, if possible
				wildTypeFragment = try {
					pos.makeFragment("wt-$name", "WildType")
				} catch (ex: DesignPosition.IllegalAnchorsException) {
					null
				}
			}
		}

		fun Slide.Locked.findViewOrThrow() =
			views
				.filterIsInstance<MoleculeRenderView>()
				.find { it.mol == mol }
				?: throw Error("can't init design position, molecule has no render view")


		fun gui(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {
			winState.render(
				onOpen = {

					// add the hover effect
					slidewin.hoverEffects[id] = hoverEffect

					// init the infos
					resetInfos(slide.findViewOrThrow())
				},
				whenOpen = {

					// draw the window
					begin("Design Position Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize))

					tabBar("tabs") {
						when (moltype) {
							MoleculeType.Protein -> tabItem("Protein") {
								renderProteinTab(imgui, slide, slidewin)
							}
							// TODO: others?
							else -> Unit
						}
						tabItem("Atoms") {
							renderAtomsTab(imgui, slide, slidewin)
						}
						tabItem(mutationsTabState, "Mutations",
							onActivated = {
								activateMutationsTab()
							},
							whenActive = {
								renderMutationsTab(imgui, slide, slidewin)
							},
							onDeactivated = {
								deactivateMutationsTab(slide)
							}
						)
					}

					end()
				},
				onClose = {
					positionEditor = null

					// cleanup effects
					slidewin.hoverEffects.remove(id)

					// cleanup the infos
					anchorGroupInfos.forEach { it.removeEffects() }
					anchorGroupInfos.clear()
					currentAtoms.forEach { it.removeEffect() }
					currentAtoms.clear()
				}
			)
		}

		private fun <R> hoveredAtom(slidewin: SlideCommands, block: (MoleculeRenderView, Atom) -> R): R? {

			// select the atom from the click, if any
			slidewin.mouseTarget?.let { target ->
				(target.view as? MoleculeRenderView)?.let { view ->

					// make sure we're picking from the same molecule
					if (view.mol == mol) {

						(target.target as? Atom)?.let { atom ->

							// found an elligible atom!
							return block(view, atom)
						}
					}
				}
			}

			// no elligible atom hovered
			return null
		}

		private var selectedChain: Polymer.Chain? = null
		private var selectedRes: Polymer.Residue? = null

		private fun renderProteinTab(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

			if (mol !is Polymer) return

			// select an initial chain if needed
			if (selectedChain == null) {
				selectedChain = mol.chains.first()
			}

			// did we pointing at an atom?
			val hoveredRes = hoveredAtom(slidewin) { view, atom ->

				// was it a residue?
				val res = mol.findResidue(atom)
				if (res != null) {

					// did we click on it?
					if (clickTracker.clicked(slidewin)) {
						selectResidue(view, res)
					}
				}

				res
			}

			// show all the chains as radios
			text("Chains:")
			columns(5)
			for (chain in mol.chains) {
				if (radioButton(chain.id, selectedChain == chain)) {
					selectedChain = chain
				}
				nextColumn()
			}
			columns(1)

			// we should definitely have a chain by now
			val selectedChain = selectedChain ?: return

			val view = slide.findViewOrThrow()

			// show all the residues as radios
			text("Residues:")
			beginChild("residues", 500f, 400f, true)
			columns(5)
			for (res in selectedChain.residues) {
				if (radioButton("${res.id} ${res.type}", res == selectedRes || res == hoveredRes)) {
					slidewin.showExceptions {
						selectResidue(view, res)
					}
				}
				nextColumn()
			}
			columns(1)
			endChild()
		}

		private fun selectResidue(view: MoleculeRenderView, res: Polymer.Residue) {

			// save the selection
			selectedRes = res

			// update the design position
			Proteins.setDesignPosition(pos, res)
			pos.resetConfSpace()
			resetInfos(view)
		}

		private var atomClickHandler: ((MoleculeRenderView, Atom) -> Unit)? = null

		private fun atomClickSidechain(view: MoleculeRenderView, atom: Atom) {
			toggleCurrentAtom(atom, view)
		}

		private fun renderAtomsTab(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

			var posChanged = false

			// default to the sidechain atom click handler
			if (atomClickHandler == null) {
				atomClickHandler = ::atomClickSidechain
			}

			// did we click on an atom?
			hoveredAtom(slidewin) { view, atom ->
				if (clickTracker.clicked(slidewin)) {

					// invoke the atom click handler
					atomClickHandler?.invoke(view, atom)
				}
			}

			// edit the name
			nameBuf.text = pos.name
			inputText("Name", nameBuf)
			pos.name = nameBuf.text

			spacing()

			// show current atoms (but call them "sidechain" atoms I guess?)
			if (radioButton("Sidechain Atoms: ${currentAtoms.size}", atomClickHandler == ::atomClickSidechain)) {
				atomClickHandler = ::atomClickSidechain
			}
			indent(20f)
			checkbox("Automatically select hydrogens", autoSelectHydrogens)
			beginChild("currentAtoms", 300f, 200f, true)
			for (info in currentAtoms) {
				selectable(info.label, info.pSelected)
			}
			endChild()

			styleEnabledIf(currentAtoms.any { it.pSelected.value }) {
				if (button("Remove")) {
					val selectedAtoms = currentAtoms
						.filter { it.pSelected.value }
						.map { it.atom }
						.toIdentitySet()
					removeCurrentAtoms(selectedAtoms, slide.findViewOrThrow())
				}
			}

			unindent(20f)
			spacing()

			// TODO: cleanup anchor rendering code? the indentation is getting a little ridiculous...

			// show anchors
			text("Anchors:")
			sameLine()
			infoTip("""
				|Anchor atoms are mainchain atoms that help align the sidechain to the mainchain.
			""".trimMargin())
			indent(20f)

			for ((anchorsi, anchorGroupInfo) in anchorGroupInfos.withIndex()) {

				// breathe a little
				if (anchorsi > 0) {
					spacing()
					separator()
					spacing()
				}

				text("Anchor Group:")
				sameLine()
				withId(anchorGroupInfo) {

					// button to delete anchor group
					if (button("x")) {
						pos.anchorGroups.remove(anchorGroupInfo.anchors)
						posChanged = true
					}

					for (anchorInfo in anchorGroupInfo.anchorInfos) {
						indent(20f)
						withId(anchorInfo) {

							fun anchorDeleteButton() {
								if (button("x")) {
									anchorGroupInfo.anchors.remove(anchorInfo.anchor)
									posChanged = true
								}
							}

							fun anchorRadioButton(name: String, atom: Atom, anchorUpdater: (Atom) -> DesignPosition.Anchor) {
								val atomInfo = anchorInfo.findAtomInfo(atom)
								if (atomInfo == null) {
									text("$name: (error)")
								} else {
									// TODO: BUGBUG: having the same atom in multiple spots causes all the radios to light up
									if (radioButton("$name: ${atomInfo.label}", atomClickHandler === atomInfo.atomClickHandler)) {

										/* This is a little tricky, so let me explain:

											When the radio is clicked, we make a function that:
												1. creates new anchor with the selected atom by calling anchorUpdater()
												2. replaces the old anchor with the new anchor in the anchor group
												3. rebuilds all the GUI shadow info for the design position by calling resetInfos()
												4. resets the atoms tab atom click handler

											Then we assign that function to the atom info for the anchor atom so we can find it again later.
											And we also assign it to the atoms tab atom click handler, so it's activated.

											Conveniently, resetInfos() will drop this function from the heap when
											it re-creates all the AnchorInfos instances, so we won't have stale handlers hanging around
										*/
										atomInfo.atomClickHandler = { view, atom ->
											anchorGroupInfo.replaceAnchor(
												anchorInfo.anchor,
												anchorUpdater(atom)
											)
											pos.resetConfSpace()
											/* NOTE:
												Could set posChanged = true here, but it won't work the way you'd think.
												By the time atomClickHandler gets called, we'll be on a different stack frame,
												and a different instance of posChanged.
												So just call resetInfos() directly. Which is ok here, since
												atomClickHandler gets called outside of the info render loops.
											 */
											resetInfos(view)
											atomClickHandler = null
										}
										atomClickHandler = atomInfo.atomClickHandler
									}
								}
							}

							when (anchorInfo.anchor) {
								is DesignPosition.SingleAnchor -> {
									text("Single Anchor")
									sameLine()
									infoTip("""
										|Single anchors allow the sidechain to bond to the mainchain at one atom.
										|Three atoms are required: a, b, and c.
										|Only the a atom is allowed to bond to the sidechain.
										|First, a positions are exactly superposed.
										|Then, a->b vectors are made parallel.
										|Then, a,b,c planes are made parallel.
									""".trimMargin())
									sameLine()
									anchorDeleteButton()

									anchorRadioButton("a", anchorInfo.anchor.a) { pickedAtom ->
										anchorInfo.anchor.copy(a = pickedAtom)
									}
									anchorRadioButton("b", anchorInfo.anchor.b) { pickedAtom ->
										anchorInfo.anchor.copy(b = pickedAtom)
									}
									anchorRadioButton("c", anchorInfo.anchor.c) { pickedAtom ->
										anchorInfo.anchor.copy(c = pickedAtom)
									}
								}
								is DesignPosition.DoubleAnchor -> {
									text("Double Anchor")
									sameLine()
									infoTip("""
										|Double anchors allow the sidechain to bond to the mainchain at two atoms.
										|Four atoms are required: a, b, c, and d.
										|Only the a and b atoms are allowed to bond to the sidechain.
										|TODO: describe alignment
									""".trimMargin())
									sameLine()
									anchorDeleteButton()

									anchorRadioButton("a", anchorInfo.anchor.a) { pickedAtom ->
										anchorInfo.anchor.copy(a = pickedAtom)
									}
									anchorRadioButton("b", anchorInfo.anchor.b) { pickedAtom ->
										anchorInfo.anchor.copy(b = pickedAtom)
									}
									anchorRadioButton("c", anchorInfo.anchor.c) { pickedAtom ->
										anchorInfo.anchor.copy(c = pickedAtom)
									}
									anchorRadioButton("d", anchorInfo.anchor.d) { pickedAtom ->
										anchorInfo.anchor.copy(d = pickedAtom)
									}
								}
							}
						}

						unindent(20f)
					}

					if (button("Add Single Anchor")) {
						slidewin.showExceptions {
							anchorGroupInfo.anchors.add(pos.SingleAnchor(
								// pick dummy atoms for now
								a = mol.atoms[0],
								b = mol.atoms[1],
								c = mol.atoms[2]
							))
							posChanged = true
						}
					}

					sameLine()

					if (button("Add Double Anchor")) {
						slidewin.showExceptions {
							anchorGroupInfo.anchors.add(pos.DoubleAnchor(
								// pick dummy atoms for now
								a = mol.atoms[0],
								b = mol.atoms[1],
								c = mol.atoms[2],
								d = mol.atoms[3]
							))
							posChanged = true
						}
					}
				}
			}

			unindent(20f)

			spacing()

			if (button("Add Anchor Group")) {
				pos.anchorGroups.add(ArrayList())
				posChanged = true
			}

			// finally, handle any pending updates after we changed the position
			if (posChanged) {
				pos.resetConfSpace()
				resetInfos(slide.findViewOrThrow())
			}
		}

		private val conflibPicker = ConfLibPicker(prep).apply {
			onAdd = { activateMutationsTab() }
		}

		inner class FragInfo(
			val id: String,
			val frag: ConfLib.Fragment
		) {
			val pSelected = Ref.of(false)
		}

		private val fragInfos = ArrayList<FragInfo>()

		private var selectedFrag: ConfLib.Fragment? = null

		private fun activateMutationsTab() {

			fun makeInfo(conflib: ConfLib?, frag: ConfLib.Fragment): FragInfo {

				val id = conflib?.fragRuntimeId(frag) ?: "dynamic.${frag.id}"

				return FragInfo(id, frag).apply {

					// is this fragment selected?
					pSelected.value = pos.confSpace.mutations.contains(frag)
				}
			}

			// rebuild the fragment infos
			fragInfos.clear()

			// add the wild type fragment first, if we can
			val wildTypeFrag = pos.confSpace.wildTypeFragment
			if (wildTypeFrag != null) {
				fragInfos.add(makeInfo(null, wildTypeFrag))
			}

			// select the wildtype fragment by default
			selectedFrag = wildTypeFrag

			// add fragments from the libraries
			for (conflib in prep.conflibs) {
				conflib.fragments.values
					.filter { pos.isFragmentCompatible(it) }
					.sortedBy { it.name }
					.forEach { frag ->
						fragInfos.add(makeInfo(conflib, frag))
					}
			}

			// TODO: collect tags for the fragments? eg, hydrophobic, aromatic
		}

		private fun renderMutationsTab(imgui: Commands, slide: Slide.Locked, slidewin: SlideCommands) = imgui.run {

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
			beginChild("mutations", 300f, 400f, true)
			if (fragInfos.isNotEmpty()) {
				for (info in fragInfos) {
					if (radioButton("###${System.identityHashCode(info.frag)}", selectedFrag == info.frag)) {
						selectedFrag = info.frag
						mutate(slide.findViewOrThrow(), info.frag)
					}
					sameLine()
					if (checkbox(info.frag.name, info.pSelected)) {

						// mark the mutation as included or not in the design position conf space
						if (info.pSelected.value) {
							pos.confSpace.mutations.add(info.frag)
						} else {
							pos.confSpace.mutations.remove(info.frag)
						}
					}
				}
			} else {
				text("(no compatible mutations)")
			}
			endChild()
		}

		private fun deactivateMutationsTab(slide: Slide.Locked) {

			val view = slide.findViewOrThrow()

			// restore the wildtype if needed
			pos.confSpace.wildTypeFragment?.let { mutate(view, it) }

			selectedFrag = null
		}

		private fun mutate(view: MoleculeRenderView, frag: ConfLib.Fragment) {

			// pick an arbitrary conformation from the fragment
			val conf = frag.confs.values.first()
			pos.setConf(frag, conf)

			// update the view
			resetInfos(view)
			view.moleculeChanged()
		}
	}
	private var positionEditor: PositionEditor? = null

	private data class PosInfo(val pos: DesignPosition, val moltype: MoleculeType) {

		val pSelected = Ref.of(false)
	}

	private val posInfosByMol = HashMap<Molecule,ArrayList<PosInfo>>()

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
				for ((moltype, mol) in prep.getIncludedTypedMols()) {
					val infos = posInfosByMol.getOrPut(mol) { ArrayList() }
					prep.designPositionsByMol.get(mol)?.forEach { pos ->
						infos.add(PosInfo(pos, moltype))
					}
				}
			},
			whenOpen = {

				// draw the window
				begin("Mutation Editor##${slide.name}", winState.pOpen, IntFlags.of(Commands.BeginFlags.AlwaysAutoResize))

				for ((i, molAndMoltype) in prep.getIncludedTypedMols().withIndex()) {
					// alas, Kotlin doesn't support nested destructuring declarations
					val (moltype, mol) = molAndMoltype
					withId(mol) {

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
						beginChild("positions", 300f, 200f, true)
						for (posInfo in posInfos) {
							selectable(posInfo.pos.name, posInfo.pSelected)
						}
						endChild()

						if (button("Add")) {
							makeNewPosition(mol, moltype)
						}

						sameLine()

						val canEdit = posInfos.count { it.pSelected.value } == 1
						styleEnabledIf(canEdit) {
							if (button("Edit") && canEdit) {
								posInfos
									.find { it.pSelected.value }
									?.let {
										positionEditor = PositionEditor(it.pos, it.moltype)
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
										prep.designPositionsByMol[mol]?.remove(it.pos)
										prep.positionConfSpaces.remove(it.pos)
									}
							}
						}
					}
				}

				// render the position editor, when active
				positionEditor?.gui(imgui, slide, slidewin)

				end()
			},
			onClose = {

				// cleanup
				posInfosByMol.clear()
			}
		)
	}

	private fun makeNewPosition(mol: Molecule, moltype: MoleculeType) {

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
		val pos = DesignPosition("$prefix$num", mol)
		positions.add(pos)

		posInfosByMol.getValue(mol).add(PosInfo(pos, moltype))

		// start the position editor
		positionEditor = PositionEditor(pos, moltype)
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
