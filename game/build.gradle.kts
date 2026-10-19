plugins {
    application
}

val jme = "3.9.0-stable"
val originalData = rootProject.extra["originalData"] as File

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
    systemProperty("citadel.data", originalData.absolutePath)
    // gradle :game:run -Ppace=20  to try a different game speed
    (project.findProperty("pace") as String?)?.let { systemProperty("citadel.pace", it) }
}

// ./gradlew :game:shot  renders one frame of a level to build/shots and exits.
tasks.register<JavaExec>("shot") {
    group = "citadel"
    description = "Renders a screenshot of a level for verification."
    mainClass.set("citadel.game.CitadelApp")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("citadel.assets", rootProject.file("build/assets").absolutePath)
    systemProperty("citadel.data", originalData.absolutePath)
    systemProperty("citadel.screenshot", rootProject.file("build/shots").absolutePath + "/")
    // jME's ScreenshotAppState writes into the folder but never creates it
    doFirst { rootProject.file("build/shots").mkdirs() }
    (project.findProperty("screen") as String?)?.let { systemProperty("citadel.screen", it) }
    (project.findProperty("walk") as String?)?.let { systemProperty("citadel.walk", it) }
    (project.findProperty("cam") as String?)?.let { systemProperty("citadel.cam", it) }
    (project.findProperty("at") as String?)?.let { systemProperty("citadel.at", it) }
    (project.findProperty("frame") as String?)?.let { systemProperty("citadel.frame", it) }
    (project.findProperty("cheat") as String?)?.let { systemProperty("citadel.cheat", it) }
    (project.findProperty("hurt") as String?)?.let { systemProperty("citadel.hurt", it) }
    (project.findProperty("pace") as String?)?.let { systemProperty("citadel.pace", it) }
    args((project.findProperty("level") as String?) ?: "LEVEL01A")
}

// ---------------------------------------------------------------- packaging
//
// gradle :game:jpackage            a self-contained folder under build/jpackage/Citadel
// gradle :game:jpackage -Ptype=msi an installer, which needs the WiX toolset on Windows
//
// jpackage substitutes $APPDIR in --java-options, so the game finds its assets next to itself
// rather than relative to whatever directory it was launched from.

val appName = "Citadel"

/** The LWJGL native classifier for the machine this is built on. */
val nativeTag: String = run {
    val os = org.gradle.internal.os.OperatingSystem.current()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it.startsWith("arm") }
    when {
        os.isWindows -> "natives-windows"
        os.isMacOsX -> if (arm) "natives-macos-arm64" else "natives-macos"
        else -> if (arm) "natives-linux-arm64" else "natives-linux"
    }
}

val stageDir = layout.buildDirectory.dir("jpackage/input")
val outDir = layout.buildDirectory.dir("jpackage")

/** The jars, the converted assets and the original data, laid out the way jpackage wants them. */
val packageInput by tasks.registering(Sync::class) {
    group = "citadel"
    description = "Stages everything jpackage needs into build/jpackage/input."
    dependsOn(":tools:convert")
    into(stageDir)
    from(tasks.named("jar"))
    // jME pulls LWJGL's natives for every platform it supports; a package is for one of them
    from(configurations.runtimeClasspath) {
        exclude { it.name.contains("-natives-") && !it.name.endsWith("-$nativeTag.jar") }
    }
    from(rootProject.file("build/assets")) {
        into("assets")
        // the map dumps are there to be read by a person, not by the game
        exclude("maps/**")
    }
    from(originalData) {
        into("Data")
    }
}

tasks.register<Exec>("jpackage") {
    group = "citadel"
    description = "Builds a self-contained application with jpackage."
    dependsOn(packageInput)

    val toolchain = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    val type = (project.findProperty("type") as String?) ?: "app-image"

    doFirst {
        val home = toolchain.get().metadata.installationPath.asFile
        val exe = listOf("bin/jpackage.exe", "bin/jpackage")
            .map { File(home, it) }
            .firstOrNull { it.isFile }
            ?: throw GradleException("no jpackage in $home; a JDK 14 or later is needed")

        val out = outDir.get().asFile
        File(out, appName).deleteRecursively()

        val options = mutableListOf(
            exe.absolutePath,
            "--type", type,
            "--name", appName,
            "--app-version", "1.0",
            "--vendor", "Guillaume Monet",
            "--description", "Citadel, the 1995 Amiga game, ported to Java and jMonkeyEngine",
            "--input", stageDir.get().asFile.absolutePath,
            "--dest", out.absolutePath,
            "--main-jar", tasks.named<Jar>("jar").get().archiveFileName.get(),
            "--main-class", "citadel.game.CitadelApp",
            "--java-options", "-Dcitadel.assets=\$APPDIR/assets",
            "--java-options", "-Dcitadel.data=\$APPDIR/Data",
            "--java-options", "-Xmx1g",
            // without this jlink keeps the whole JDK, which is a hundred megabytes of nothing.
            // java.desktop is ImageIO and AWT, jdk.unsupported is the sun.misc.Unsafe LWJGL wants
            "--add-modules", "java.base,java.desktop,java.logging,java.management,jdk.unsupported",
        )
        if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
            // LWJGL wants the main thread on macOS
            options += listOf("--java-options", "-XstartOnFirstThread")
        }
        if (type != "app-image") {
            options += listOf("--win-shortcut", "--win-menu", "--win-dir-chooser")
        }
        commandLine(options)
        logger.lifecycle("jpackage --type $type -> ${out.absolutePath}")
    }
}
