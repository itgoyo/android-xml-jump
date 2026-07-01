plugins {
    id("org.jetbrains.intellij.platform") version "2.17.0"
    kotlin("jvm") version "2.0.21"
}

group = "com.itgoyo"
version = "0.1.5"

kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    google()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        androidStudio("2024.2.2.13")
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.itgoyo.android-xml-jump"
        name = "Android XML Jump"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }

        changeNotes = """
            <ul>
              <li>Fix XML caret: navigate via PSI XmlTag (works with Android layout editor).</li>
              <li>Retry caret placement and scroll-to-center after editor opens asynchronously.</li>
              <li>Restore caret after switching to Split (Code + Design) view.</li>
              <li>Improve view-id extraction from binding.guideIV and R.id.xxx.</li>
            </ul>
        """.trimIndent()
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default")
    }
}
