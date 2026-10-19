plugins {
    java
}

// The original Amiga sources and data (https://github.com/pawelmat/Amiga-Citadel) are not part of
// this repository. They are expected in a sibling checkout, or wherever -Poriginal points:
//   gradle :game:run -Poriginal=/path/to/Amiga-Citadel
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
    }
}
