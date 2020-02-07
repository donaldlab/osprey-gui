package edu.duke.cs.ospreygui.tools

import kotlin.random.Random


/**
 * Returns all unique pairs of things in the list
 */
fun <T> List<T>.pairs(): List<Pair<T,T>> =
	mapIndexed { i, item ->
		subList(0, i)
			.map { item to it }
	}
	.flatten()


class UnsupportedClassException(val msg: String, val obj: Any)
	: RuntimeException("$msg: ${obj::class.simpleName}")


fun Random.nextFloatIn(min: Float, max: Float): Float =
	if (min != max) {
		nextDouble(min.toDouble(), max.toDouble()).toFloat()
	} else {
		min
	}
