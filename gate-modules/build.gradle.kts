plugins {
    id("java-library")
}

group = "dev.gate"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // Modules build on top of the minimal core (Context, Handler, Gate, Database, ...)
    api(project(":gate-core"))

    implementation("org.yaml:snakeyaml:2.2")             // YamlRouteLoader
    implementation("ch.qos.logback:logback-classic:1.4.11") // LogBuffer, CancelledKeyExceptionFilter
    implementation("org.slf4j:slf4j-api:2.0.9")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}
