plugins {
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("citadel.tools.Convert")
}

// ./gradlew :tools:convert  regenerates build/assets from the original Data folder.
tasks.register<JavaExec>("convert") {
    group = "citadel"
    description = "Converts the original Amiga data into PNG, WAV and text dumps."
    mainClass.set("citadel.tools.Convert")
    classpath = sourceSets["main"].runtimeClasspath
    args("../Data", "../build/assets")
}

// ./gradlew :tools:inspect -Pargs="Data/GAME/MAPS/LEVEL01A.MAP 24 7"
tasks.register<JavaExec>("inspect") {
    group = "citadel"
    description = "Prints the decoded contents of a window of a level's grid."
    mainClass.set("citadel.tools.Inspect")
    classpath = sourceSets["main"].runtimeClasspath
    args((project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotEmpty() })
}
