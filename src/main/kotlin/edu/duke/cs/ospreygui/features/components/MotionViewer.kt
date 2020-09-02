package edu.duke.cs.ospreygui.features.components

import cuchaz.kludge.imgui.Commands
import edu.duke.cs.molscope.molecule.Atom
import edu.duke.cs.molscope.view.MoleculeRenderView
import kotlin.random.Random


interface MotionViewer {

	val label: String
	fun gui(imgui: Commands)
	fun jiggle(rand: Random)
	fun reset()
	fun mapAtomToOriginalMol(atom: Atom): Atom
}
