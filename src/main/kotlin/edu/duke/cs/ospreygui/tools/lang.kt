package edu.duke.cs.ospreygui.tools


/**
 * Returns all unique pairs of things in the list
 */
fun <T> List<T>.pairs(): List<Pair<T,T>> =
	mapIndexed { i, item ->
		subList(0, i)
			.map { item to it }
	}
	.flatten()
