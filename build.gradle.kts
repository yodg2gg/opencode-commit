plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.github.opencodecommit"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    intellijPlatform {
        // 目标平台：IntelliJ IDEA Community 2024.3（since-build 243）
        create("IC", "2024.3")
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "243"
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
    buildSearchableOptions = false
    instrumentCode.set(false)
}
