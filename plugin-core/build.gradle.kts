plugins {
    kotlin("jvm")
    id("org.openstreetmap.josm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // JOSM plugins ship as a single jar and the Kotlin stdlib is not on JOSM's
    // classpath, so it has to travel with us.
    packIntoJar(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")
}

josm {
    pluginName = "lanelet2"
    debugPort = 1740
    josmCompileVersion = "19555"
    manifest {
        description = "Lanelet2 map editing tools for JOSM."
        mainClass = "org.openstreetmap.josm.plugins.lanelet2.Lanelet2Plugin"
        minJosmVersion = "19555"
        author = "MRT"
        canLoadAtRuntime = true
    }
}
