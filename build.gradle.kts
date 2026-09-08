plugins {
    kotlin("jvm") version "2.1.0"
    id("org.openstreetmap.josm") version "0.8.2"
}

group = "org.openstreetmap.josm.plugins.lanelet2"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
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
    // JarResourceLoadingTest inspects the packaged jar rather than the class
    // output, so that resource paths are verified as JOSM will see them.
    dependsOn("dist")
    systemProperty(
        "lanelet2.jar",
        layout.buildDirectory.file("dist/lanelet2.jar").get().asFile.absolutePath,
    )
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
