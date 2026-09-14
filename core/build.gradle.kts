// Data formats and the runtime level model. No runtime dependencies and no java.desktop, so this
// module can be reused anywhere: the converter, the game, or a headless test.

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
