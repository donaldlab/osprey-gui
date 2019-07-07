package edu.duke.cs.ospreygui.io

import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue


/**
 * Reads stdout and stderr from a process and sends the lines
 * back in ConcurrentLinkedQueue instances.
 */
class ProcessStreamer(val process: Process) {

	val stdout = ConcurrentLinkedQueue<String>()
	val stderr = ConcurrentLinkedQueue<String>()

	val threadOut = process.inputStream.streamTo(stdout)
	val threadErr = process.inputStream.streamTo(stderr)

	// TODO: allow timeout?
	fun waitFor() = apply {
		process.waitFor()
		threadOut.join()
		threadErr.join()
	}
}

fun Process.stream() = ProcessStreamer(this)


fun InputStream.streamTo(queue: Queue<String>) =
	Thread {
		try {
			bufferedReader(Charsets.UTF_8).forEachLine { line ->
				queue.add(line)
			}
		} catch (ex: IOException) {
			if (ex.message != "Stream closed") {
				throw ex
			}
		}
	}.apply {
		name = "ProcessStreamer"
		isDaemon = true
		start()
	}
