import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


plugins {
	kotlin("jvm") version "1.3.21"
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

tasks {

	// tell gradle to write down the version number where the app can read it
	processResources {

		from(sourceSets["main"].resources.srcDirs) {
			include("**/build.properties")
			expand(
				"version" to "$version",
				"dev" to "true" // TEMP: set to false in "release" builds
			)
		}
	}
}
