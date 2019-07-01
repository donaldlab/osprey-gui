package edu.duke.cs.ospreygui

import io.kotlintest.*
import io.kotlintest.specs.FunSpec


/**
 * A KotlinTest spec that allows sharing setup and state between tests
 */
open class SharedSpec(body: SharedSpec.() -> Unit = {}) : FunSpec() {

	// use a single test instance, so state gets shared rather than re-computed
	override fun isolationMode() = IsolationMode.SingleInstance

	init {
		body()
	}
}

class TestSharedSpec : SharedSpec({

	var globalCounter = 0

	test("test 1") {
		++globalCounter shouldBe 1
	}

	test("test 2") {
		++globalCounter shouldBe 2
	}

	context("suite") {

		var suiteCounter = 0

		test("test 3") {
			++globalCounter shouldBe 3
			++suiteCounter shouldBe 1
		}

		test("test 4") {
			++globalCounter shouldBe 4
			++suiteCounter shouldBe 2
		}
	}
})