package edu.duke.cs.ospreygui.compiler


class CompilerProgress(
	vararg val tasks: Task,
	private val threadGetter: () -> Thread
) {

	class Task(
		val name: String,
		val size: Int
	) {

		// NOTE: volatile is the lazy person's thread synchronization
		@Volatile var progress = 0

		fun increment() {
			progress += 1
		}

		val fraction get() =
			progress.toFloat()/size.toFloat()
	}

	@Volatile var report: ConfSpaceCompiler.Report? = null

	/**
	 * Blocks the current thread until the compiler thread finishes.
	 */
	fun waitForFinish() {
		threadGetter().join()
	}
}
