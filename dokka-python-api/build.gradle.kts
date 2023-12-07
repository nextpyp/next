
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


plugins {
	kotlin("jvm")
	// no version needed, since this is a subproject
}

group = "edu.duke.bartesaghi"

repositories {
	mavenCentral()
}

dependencies {
	val kotlinVersion: String by System.getProperties()
	implementation("org.jetbrains.dokka:dokka-base:$kotlinVersion")
	implementation("org.jetbrains.dokka:dokka-core:$kotlinVersion")
}

tasks.withType<KotlinCompile> {
	kotlinOptions.jvmTarget = "1.8"
}
