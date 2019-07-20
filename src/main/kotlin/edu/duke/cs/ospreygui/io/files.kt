package edu.duke.cs.ospreygui.io

import org.apache.commons.io.FileUtils
import java.nio.file.Files
import java.nio.file.Path


fun String.write(path: Path) {
	path.toFile().writeText(this, Charsets.UTF_8)
}

fun Path.read(): String =
	toFile().readText(Charsets.UTF_8)


val Path.exists get() = Files.exists(this)


inline fun <R> tempFolder(name: String, block: (Path) -> R): R {
	val dir = Files.createTempDirectory(name)
	try {
		return block(dir)
	} finally {
		FileUtils.deleteDirectory(dir.toFile())
	}
}
