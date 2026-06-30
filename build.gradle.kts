plugins {
    id("org.jetbrains.intellij.platform") version "2.17.0"
    kotlin("jvm") version "2.0.21"
}

group = "com.itgoyo"
version = "0.1.0"

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
              <li>Add editor tab action and keyboard shortcut to jump from Android layout XML files to classes that reference them.</li>
              <li>Support common Android references including R.layout, @layout, ViewBinding, and DataBinding usage.</li>
            </ul>
        """.trimIndent()
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default")
    }
}
