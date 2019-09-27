package edu.duke.cs.ospreygui.io

import org.tomlj.TomlArray
import org.tomlj.TomlPosition
import org.tomlj.TomlTable


fun TomlTable.getTableOrThrow(key: String, pos: TomlPosition? = null) =
	getTable(key) ?: throw TomlParseException("missing field \"$key\", or it is not a string", pos)

fun TomlTable.getStringOrThrow(key: String, pos: TomlPosition? = null) =
	getString(key) ?: throw TomlParseException("missing field \"$key\", or it is not a string", pos)

fun TomlTable.getArrayOrThrow(key: String, pos: TomlPosition? = null) =
	getArray(key) ?: throw TomlParseException("missing field \"$key\", or it is not an array", pos)

fun TomlTable.getDoubleOrThrow(key: String, pos: TomlPosition? = null) =
	getDouble(key) ?: throw TomlParseException("missing field \"$key\", or it is not a floating-point number", pos)

fun TomlTable.getIntOrThrow(key: String, pos: TomlPosition? = null) =
	getLong(key)?.toInt() ?: throw TomlParseException("missing field \"$key\", or it is not an integer", pos)

fun TomlArray.getInt(index: Int) =
	getLong(index).toInt()

class TomlParseException(msg: String, val pos: TomlPosition? = null) : RuntimeException(
	msg + if (pos != null) " at $pos" else ""
)
