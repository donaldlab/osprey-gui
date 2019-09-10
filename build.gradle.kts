import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


plugins {
	kotlin("jvm") version "1.3.21"
	application
}


group = "edu.duke.cs"
version = "0.1"

repositories {
	jcenter()
}

dependencies {
	
	implementation(kotlin("stdlib-jdk8"))
	implementation("edu.duke.cs:molscope")
	implementation("edu.duke.cs:osprey3")

	testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.0")
}

configure<JavaPluginConvention> {
	sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile> {

	kotlinOptions {

		jvmTarget = "1.8"

		// enable experimental features
		languageVersion = "1.3"
		freeCompilerArgs += "-XXLanguage:+InlineClasses"
	}
}

// Make a value that can be set after processResources has configured itself.
// ie, the value can be set after we know about the task graph,
// and we can tell if this a dev or release build
class ResourceVal<T>(var value: T) {
	override fun toString() = value.toString()
}
val isDev = ResourceVal(true)

gradle.taskGraph.whenReady {

	// assume we're doing a release build if we're calling the jar task
	if (hasTask(":jar")) {
		isDev.value = false
	}
}

tasks {

	// tell gradle to write down the version number where the app can read it
	processResources {

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
}

application {
	mainClassName = "edu.duke.cs.ospreygui.MainKt"
}

distributions {

	fun CopySpec.sharedBuildInfo() {

		// get all the java build stuff from the "main" distribution
		with(get("main").contents)

		// add extra documentation
		into("") { // project root
			from("readme.md")
			from("LICENSE.txt")
			// TODO: contributing.md?
		}

		// add amber tools dat files
		into("ambertools") {
			into("dat") {
				from("ambertools/dat")
			}
		}
	}

	create("linux-amd64").apply {
		baseName = "${project.name}-$name"
		contents {

			// skip the windows startup script
			exclude("${project.name}.bat")

			sharedBuildInfo()

			into("ambertools/bin") {
				from("ambertools/linux/amd64")
			}
		}
	}

	create("windows-x64").apply {
		baseName = "${project.name}-$name"
		contents {

			// skip the *nix startup script
			exclude(project.name)

			sharedBuildInfo()

			into("ambertools/bin") {
				// TODO: compile ambertools binaries in Win10
				from("ambertools/windows/x64")
			}
		}
	}

	create("macos-x64").apply {
		baseName = "${project.name}-$name"
		contents {

			// skip the windows startup script
			exclude("${project.name}.bat")

			sharedBuildInfo()

			into("ambertools/bin") {
				// TODO: compile ambertools binaries in OSX
				from("ambertools/macos/x64")
			}
		}
	}
}

tasks {

	// turn off the "main" distribution
	distZip {
		enabled = false
	}
	distTar {
		enabled = false
	}

	// turn off tar distributions
	for (task in this) {
		if (task.name.endsWith("DistTar")) {
			task.enabled = false
		}
	}
}
