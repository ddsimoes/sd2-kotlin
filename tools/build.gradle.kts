plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

group = "io.github.ddsimoes.sd2"
version = "0.1.0"

repositories {
    mavenCentral()
}

application {
    mainClass.set("io.github.sd2.tools.cli.MainKt")
}

dependencies {
    implementation(project(":sd2-parser"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
