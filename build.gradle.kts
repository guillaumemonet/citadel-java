plugins {
    java
}

// The game reads the five original floppy images (.adf), which are not part of this repository.
// They are expected in ../adf, or wherever -Padf points:
//   gradle :game:run -Padf=/path/to/adf
extra["adfDir"] = file((findProperty("adf") as String?) ?: "../adf")

// The original Amiga sources and data (https://github.com/pawelmat/Amiga-Citadel) are only used by
// the tools and to cross-check the disks in the tests. A sibling checkout, or -Poriginal:
val original: File = file((findProperty("original") as String?) ?: "../Amiga-Citadel")
extra["originalData"] = File(original, "Data")

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        systemProperty("citadel.data", (rootProject.extra["originalData"] as File).absolutePath)
        systemProperty("citadel.adf", (rootProject.extra["adfDir"] as File).absolutePath)
    }
}
