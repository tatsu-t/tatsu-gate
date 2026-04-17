plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.gate"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":gate-core"))
}

tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "app.Main"
    }
    archiveBaseName.set("app")
    archiveClassifier.set("")
    archiveVersion.set("")
}
