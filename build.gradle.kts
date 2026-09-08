plugins {
    kotlin("jvm") version "2.1.0" apply false
    id("org.openstreetmap.josm") version "0.8.2" apply false
}

allprojects {
    group = "org.openstreetmap.josm.plugins.lanelet2"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
