plugins {
    java
}

group = "dev.advancedtrains"
version = "0.2.0-SNAPSHOT"

// The oldest server API ATCS compiles against. Anything newer in the 1.21 line keeps working,
// so stick to this API surface instead of bumping it for convenience.
val paperApiVersion = "1.21.4-R0.1-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "PaperMC"
    }
    maven("https://ci.mg-dev.eu/plugin/repository/everything/") {
        name = "MGDev"
    }
    maven("https://repo.bluecolored.de/releases") {
        name = "BlueColored"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    // Provided by the server. Only the long-stable parts of these APIs are used, so any
    // TrainCarts 2.x build works; never add an exact-version check at runtime.
    compileOnly("com.bergerkiller.bukkit:TrainCarts:2.0.0") { isTransitive = false }
    compileOnly("com.bergerkiller.bukkit:BKCommonLib:2.0.1") { isTransitive = false }

    // Optional integrations, activated only when the plugin is installed.
    compileOnly("com.bergerkiller.bukkit:SignLink:1.21.5-v1") { isTransitive = false }
    compileOnly("de.bluecolored:bluemap-api:2.7.3")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val englishPolicyFiles = fileTree(projectDir) {
    include("src/**/*.java", "src/**/*.yml", "*.md", "docs/**/*.md")
    exclude("build/**")
}

val verifyEnglishTextPolicy by tasks.registering {
    group = "verification"
    description = "Rejects CJK text in source, bundled configuration, and documentation."
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
            throw GradleException("English-only text policy violations:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
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
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.check {
    dependsOn(verifyEnglishTextPolicy)
}

// Writes docs/reference/*.md from the command tree, sign parsers, plugin.yml and config.yml.
// ReferenceDocsTest fails the build when the committed pages differ from what this writes.
val generateDocs by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Regenerates the reference pages under docs/reference from the code."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.advancedtrains.atcs.command.ReferenceDocs")
    args("docs/reference")
    workingDir = projectDir
}

tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
