plugins {
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("citadel.tools.Convert")
}

val originalData = rootProject.extra["originalData"] as File

// ./gradlew :tools:convert  regenerates build/assets from the original Data folder.
tasks.register<JavaExec>("convert") {
    group = "citadel"
    description = "Converts the original Amiga data into PNG, WAV and text dumps."
    mainClass.set("citadel.tools.Convert")
    classpath = sourceSets["main"].runtimeClasspath
    args(originalData.absolutePath, rootProject.file("build/assets").absolutePath)
}

// ./gradlew :tools:inspect -Pargs="../../Amiga-Citadel/Data/GAME/MAPS/LEVEL01A.MAP 24 7"
tasks.register<JavaExec>("inspect") {
    group = "citadel"
    description = "Prints the decoded contents of a window of a level's grid."
    mainClass.set("citadel.tools.Inspect")
    classpath = sourceSets["main"].runtimeClasspath
    args((project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotEmpty() })
}

// ./gradlew :tools:anim -Pargs="../../Amiga-Citadel/Data/INTRO/ANIM2/start"
tasks.register<JavaExec>("anim") {
    group = "citadel"
    description = "Decodes an IFF ANIM intro sequence to PNG frames."
    mainClass.set("citadel.tools.AnimReader")
    classpath = sourceSets["main"].runtimeClasspath
    args((project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotEmpty() })
}

tasks.register<JavaExec>("iff") {
    group = "citadel"
    mainClass.set("citadel.tools.IffReader")
    classpath = sourceSets["main"].runtimeClasspath
    args((project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotEmpty() })
}
