import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.1.0"
    kotlin("plugin.serialization") version "2.0.20"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    // implementation("io.github.petertrr:kotlin-multiplatform-diff:0.7.0")

    intellijPlatform {
        intellijIdeaCommunity("2024.3")

        instrumentationTools()
        pluginVerifier()
    }
    implementation(kotlin("stdlib-jdk8"))
}

group = "com.nahco314"
version = "1.2-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    google()

    intellijPlatform {
        defaultRepositories()
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            recommended()
            select {
                ide(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3.5")
            }
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        untilBuild.set("")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
kotlin {
    jvmToolchain(17)
}