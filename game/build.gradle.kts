plugins {
    application
}

val jme = "3.9.0-stable"

dependencies {
    implementation(project(":core"))
    implementation("org.jmonkeyengine:jme3-core:$jme")
    implementation("org.jmonkeyengine:jme3-desktop:$jme")
    implementation("org.jmonkeyengine:jme3-lwjgl3:$jme")
}

application {
    mainClass.set("citadel.game.CitadelApp")
}

tasks.withType<JavaExec>().configureEach {
    // Assets converted by :tools:convert, and the original data for the .MAP files.
    systemProperty("citadel.assets", rootProject.file("build/assets").absolutePath)
    systemProperty("citadel.data", rootProject.file("Data").absolutePath)
}

// ./gradlew :game:shot  renders one frame of a level to build/shots and exits.
tasks.register<JavaExec>("shot") {
    group = "citadel"
    description = "Renders a screenshot of a level for verification."
    mainClass.set("citadel.game.CitadelApp")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("citadel.assets", rootProject.file("build/assets").absolutePath)
    systemProperty("citadel.data", rootProject.file("Data").absolutePath)
    systemProperty("citadel.screenshot", rootProject.file("build/shots").absolutePath + "/")
    (project.findProperty("screen") as String?)?.let { systemProperty("citadel.screen", it) }
    (project.findProperty("cam") as String?)?.let { systemProperty("citadel.cam", it) }
    (project.findProperty("at") as String?)?.let { systemProperty("citadel.at", it) }
    args((project.findProperty("level") as String?) ?: "LEVEL01A")
}
