import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.2"
}

group = "fr.aethernode"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0")
    implementation("org.spongepowered:configurate-yaml:4.1.2")
    // H2 embedded database — fast, zero-config, single file
    implementation("com.h2database:h2:2.2.224")
    // HTTP client for Discord webhooks (bundled in JDK 11+, no extra dep needed)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")

    relocate("org.spongepowered.configurate", "fr.aethernode.velocibans.libs.configurate")
    relocate("io.leangen.geantyref",          "fr.aethernode.velocibans.libs.geantyref")
    relocate("org.yaml.snakeyaml",            "fr.aethernode.velocibans.libs.snakeyaml")
    relocate("org.h2",                        "fr.aethernode.velocibans.libs.h2")

    minimize {
        // H2 uses reflection heavily — don't strip it
        exclude(dependency("com.h2database:h2:.*"))
    }
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
}
