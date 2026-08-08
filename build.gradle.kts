import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "8.3.9"
}

group = "dev.advancedtrains"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "PaperMC"
    }
    maven("https://ci.mg-dev.eu/plugin/repository/everything/") {
        name = "MGDev"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // TrainCarts calls must stay behind the integration adapter. These APIs are provided by the server.
    compileOnly("com.bergerkiller.bukkit:TrainCarts:2.0.0") {
        isTransitive = false
    }
    compileOnly("com.bergerkiller.bukkit:BKCommonLib:2.0.1") {
        isTransitive = false
    }
    compileOnly("com.bergerkiller.bukkit:SignLink:1.21.5-v1") {
        isTransitive = false
    }

    implementation("org.xerial:sqlite-jdbc:3.53.2.1") {
        // Paper already provides SLF4J. Shipping another API copy can break the server class loader.
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(platform("net.kyori:adventure-bom:4.26.1"))
    testImplementation("net.kyori:adventure-api")
    testImplementation("net.kyori:adventure-text-serializer-plain")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

val englishPolicyFiles = fileTree(projectDir) {
    include(
        "src/**/*.java",
        "src/**/*.js",
        "src/**/*.html",
        "src/**/*.css",
        "src/**/*.yml",
        "src/**/*.yaml",
        "*.md",
        "plans/**/*.md"
    )
    exclude("build/**")
}

val verifyEnglishTextPolicy by tasks.registering {
    group = "verification"
    description = "Rejects CJK text in English-only source, UI, configuration, comments, and documentation."
    inputs.files(englishPolicyFiles)
    doLast {
        val disallowedScripts = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF]")
        val violations = englishPolicyFiles.files
            .sortedBy { it.invariantSeparatorsPath }
            .flatMap { file ->
                file.readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
                    if (disallowedScripts.containsMatchIn(line)) {
                        "${file.relativeTo(projectDir).invariantSeparatorsPath}:${index + 1}"
                    } else {
                        null
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "English-only text policy violations:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

tasks.processResources {
    val properties = mapOf("version" to project.version)
    inputs.properties(properties)
    filesMatching("plugin.yml") {
        expand(properties)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.check {
    dependsOn(verifyEnglishTextPolicy)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    relocate("com.google.gson", "dev.advancedtrains.atcs.internal.gson")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
