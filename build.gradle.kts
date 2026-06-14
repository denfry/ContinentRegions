plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "com.example.continentregions"
version = "2.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")   // Paper API
    maven("https://maven.enginehub.org/repo/") {                // WorldEdit / WorldGuard
        // EngineHub ships Gradle module metadata with strict constraints
        // (gson/guava/fastutil "provided by Mojang") that clash with paper-api's
        // newer versions and break resolution. Read only the POM so those strict
        // constraints are ignored; the server provides these libs at runtime anyway.
        metadataSources {
            mavenPom()
            artifact()
            ignoreGradleMetadataRedirection()
        }
    }
    maven("https://repo.bluecolored.de/releases")               // BlueMapAPI
}

dependencies {
    // Provided by the server at runtime — never bundled into the jar.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    // Pinned to match the dev runtime (runServer): WorldEdit 7.3.11 / WorldGuard 7.0.13.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.11")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.17")
    compileOnly("de.bluecolored:bluemap-api:2.7.8")

    // Bundled (shaded) third-party runtime dependencies.
    implementation("com.google.code.gson:gson:2.11.0")
    // SQLite backend (v2). Deliberately NOT relocated: the xerial driver loads a
    // native library from a package-derived resource path, which relocation would
    // break. Bukkit's per-plugin classloader isolates it from other plugins.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        // Relocate bundled libraries to avoid clashes with other plugins / the server.
        // No minimize(): Gson resolves type adapters reflectively and minimize can strip them.
        relocate("com.google.gson", "com.example.continentregions.lib.gson")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }

    // Local dev server: downloads Paper + required plugins into run/plugins automatically.
    runServer {
        minecraftVersion("1.21.4")
        downloadPlugins {
            // WorldEdit: pinned to the 7.3.11 *bukkit* jar via direct URL. Two reasons:
            //  - 7.4.x are 2026 JDK-25 builds whose class files (major 69) Paper's
            //    plugin remapper (ASM 9.7.1) cannot read.
            //  - modrinth() grabs the version's primary file, which for WorldEdit is a
            //    non-bukkit loader jar (no plugin.yml); the URL points at the bukkit jar.
            url("https://cdn.modrinth.com/data/1u6JkXh5/versions/DlD8WKr9/worldedit-bukkit-7.3.11.jar")
            modrinth("worldguard", "7.0.13")
            modrinth("bluemap", "5.16-spigot")
        }
    }
}
