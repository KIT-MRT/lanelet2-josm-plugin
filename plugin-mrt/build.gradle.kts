plugins {
    kotlin("jvm")
    id("org.openstreetmap.josm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Resolved at runtime through JOSM's Plugin-Requires -> PluginClassLoader
    // dependency chain, so it must not be packed into this jar.
    compileOnly(project(":plugin-core"))
    compileOnly(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The classloader-chain test inspects the real distribution jars, since that
    // is the only place the packed Kotlin stdlib and the generated manifest exist.
    dependsOn(":plugin-core:dist", "dist")
    systemProperty(
        "lanelet2.coreJar",
        project(":plugin-core").layout.buildDirectory.file("dist/lanelet2.jar").get().asFile.absolutePath
    )
    systemProperty(
        "lanelet2.mrtJar",
        layout.buildDirectory.file("dist/lanelet2-mrt.jar").get().asFile.absolutePath
    )
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")
}

josm {
    pluginName = "lanelet2-mrt"
    debugPort = 1741
    josmCompileVersion = "19555"
    manifest {
        description = "MRT-internal extensions for the Lanelet2 JOSM plugin."
        mainClass = "org.openstreetmap.josm.plugins.lanelet2.mrt.Lanelet2MrtPlugin"
        minJosmVersion = "19555"
        author = "MRT"
        canLoadAtRuntime = true
    }
}

// The josm extension's `pluginDependencies` would additionally try to resolve
// "lanelet2" as a published artifact from the JOSM plugin repositories, where
// our sibling project does not exist. We only need the manifest entry: JOSM
// reads Plugin-Requires at load time and wires up the PluginClassLoader chain,
// while compilation is already covered by compileOnly(project(":plugin-core")).
val requiredJosmPlugins = listOf("lanelet2")

tasks.named("generateManifest") {
    outputs.upToDateWhen { false }
    doLast {
        val manifest = layout.buildDirectory
            .file("josm-manifest/META-INF/MANIFEST.MF").get().asFile
        val lines = manifest.readLines().filterNot { it.startsWith("Plugin-Requires:") }
        manifest.writeText(
            (lines + "Plugin-Requires: ${requiredJosmPlugins.joinToString(";")}")
                .joinToString("\n", postfix = "\n")
        )
    }
}
