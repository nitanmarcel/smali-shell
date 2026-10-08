import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.10"
}

version = "0.0.1"

repositories {
    mavenLocal()
    mavenCentral()
    google()
}

dependencies {
    compileOnly("io.github.nitanmarcel:jadx-emu:0.1.0-beta.5")

    compileOnly("io.github.skylot:jadx-core:1.5.6")
    compileOnly("com.android.tools.smali:smali:3.0.9")
    compileOnly("com.fifesoft:rsyntaxtextarea:3.6.1")
    compileOnly(kotlin("stdlib"))
    compileOnly("org.slf4j:slf4j-api:2.0.17")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.jar {
    archiveBaseName = "smali-shell"
}
