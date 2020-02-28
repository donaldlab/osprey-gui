import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.internal.os.OperatingSystem


plugins {
	kotlin("jvm") version "1.3.60"
	kotlin("plugin.serialization") version "1.3.61"
	id("org.beryx.runtime") version "1.8.0"
}


group = "edu.duke.cs"
version = "0.1"

repositories {
	jcenter()
}

dependencies {
	
	implementation(kotlin("stdlib-jdk8"))
	implementation(kotlin("reflect"))
	implementation("ch.qos.logback:logback-classic:1.2.3")
	implementation("edu.duke.cs:molscope")
	implementation("edu.duke.cs:osprey3")
	implementation("edu.duke.cs:osprey-service")

	val ktorVersion = "1.3.0"
	implementation("io.ktor:ktor-client-cio:$ktorVersion")
	implementation("io.ktor:ktor-client-serialization-jvm:$ktorVersion")

	testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.0")
}

configure<JavaPluginConvention> {
	sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile> {

	kotlinOptions {

		jvmTarget = "1.8"

		// enable experimental features so we can use the fancy ktor stuff
		freeCompilerArgs += "-Xuse-experimental=kotlin.Experimental"
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}


// assume we're doing a dev build if the top task is "classes"
if (gradle.startParameter.taskNames.any { it.endsWith(":classes") }) {
	System.setProperty("isDev", true.toString())
}
val isDev = object {
	override fun toString() = System.getProperty("isDev") ?: false.toString()
}

tasks {

	// tell gradle to write down the version number where the app can read it
	processResources {

		// always update the build properties
		outputs.upToDateWhen { false }

		from(sourceSets["main"].resources.srcDirs) {
			include("**/build.properties")
			expand(
				"version" to "$version",
				"dev" to isDev
			)
		}
	}

	// add documentation to the jar file
	jar {
		into("") { // project root
			from("readme.md")
			from("LICENSE.txt")
			// TODO: contributing.md?
		}
	}

	startScripts {
		doLast {
			// Tragically, an incompatibility between Windows and Gradle's batch script
			// causes the batch script to fail when the classpath is long and complicated.
			// This workaround isn't an ideal solution (because it destroys the classpath order),
			// but there aren't any better solutions available (yet). see:
			// https://github.com/gradle/gradle/issues/1989
			windowsScript.writeText(windowsScript.readText().replace(
				Regex("set CLASSPATH=.*"),
				"set CLASSPATH=%APP_HOME%/lib/*"
			))
		}
	}
}

application {
	mainClassName = "edu.duke.cs.ospreygui.MainKt"
}

runtime {

	options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
	modules.set(listOf("java.desktop", "java.management", "java.xml"))

	jpackage {

		jpackageHome = System.getProperty("jpackage.home")

		when (val os = OperatingSystem.current()) {

			OperatingSystem.WINDOWS -> {
				installerType = "msi"
				installerOptions = listOf("--win-per-user-install", "--win-dir-chooser", "--win-menu", "--win-shortcut")
			}
		}
	}
}

distributions {

	get("main").apply {
		contents {

			// add extra documentation
			into("") { // project root
				from("readme.md")
				from("LICENSE.txt")
				// TODO: contributing.md?
			}
		}
	}
}

tasks {

	// turn off tar distributions
	for (task in this) {
		if (task.name.endsWith("DistTar")) {
			task.enabled = false
		}
	}
}
