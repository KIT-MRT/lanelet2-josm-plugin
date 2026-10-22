package org.openstreetmap.josm.plugins.lanelet2.internal.scenes

import org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d.Viewer3dResources
import org.openstreetmap.josm.tools.Logging
import java.io.File
import java.io.IOException
import java.net.JarURLConnection
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The KITScenes scene server (`tools/scene_server/ll2_scene`), shipped in the
 * jar under [PREFIX] and extracted to `<josm-userdata>/plugins/lanelet2/scene_server`,
 * so `python -m ll2_scene` runs from there with no checkout.
 *
 * Same contract as the viewer's and the backends' extracts: re-extracted only
 * when the content hash of the shipped files changes.
 */
object SceneServerStore {
    const val PREFIX = "lanelet2/scene_server/"
    const val ANCHOR = "${PREFIX}ll2_scene/__main__.py"
    const val EXTRACT_SUBDIR = "scene_server"
    const val VERSION_FILE = ".shipped_version"
    const val PACKAGE = "ll2_scene"

    /** Shipped files, relative to [PREFIX] (`ll2_scene/city.py`, ...), sorted. */
    fun shippedFiles(loader: ClassLoader = resourceLoader()): List<String> {
        val anchor = loader.getResource(ANCHOR) ?: throw IllegalStateException("missing jar resource $ANCHOR")
        val names = when (anchor.protocol) {
            // Do not close the JarFile: JarURLConnection caches it on the plugin jar.
            "jar" -> (anchor.openConnection() as JarURLConnection).jarFile.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(PREFIX) }
                .map { it.name.removePrefix(PREFIX) }
                .toList()
            "file" -> {
                val root = File(anchor.toURI()).parentFile.parentFile
                root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
            }
            else -> throw IllegalStateException("unsupported resource protocol: ${anchor.protocol}")
        }
        return names.filter { it.endsWith(".py") }.sorted()
    }

    fun shippedVersion(loader: ClassLoader = resourceLoader()): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (name in shippedFiles(loader)) {
            md.update(name.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(read(loader, name))
        }
        return HexFormat.of().formatHex(md.digest())
    }

    fun defaultExtractDir(): File = File(Viewer3dResources.defaultExtractRoot(), EXTRACT_SUBDIR)

    /** [dir] with the current scene server in it (`dir/ll2_scene/...`). */
    fun ensureExtracted(dir: File = defaultExtractDir(), loader: ClassLoader = resourceLoader()): File {
        val version = shippedVersion(loader)
        val versionFile = File(dir, VERSION_FILE)
        if (versionFile.isFile && versionFile.readText().trim() == version &&
            shippedFiles(loader).all { File(dir, it).isFile }
        ) {
            return dir
        }
        extract(dir, loader, version)
        return dir
    }

    fun extract(dir: File, loader: ClassLoader = resourceLoader(), version: String = shippedVersion(loader)) {
        val staging = File(dir.parentFile, dir.name + ".extracting")
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("cannot create staging dir $staging")
        try {
            for (name in shippedFiles(loader)) {
                val dest = File(staging, name)
                dest.parentFile?.mkdirs()
                dest.writeBytes(read(loader, name))
            }
            File(staging, VERSION_FILE).writeText(version)
            if (dir.exists()) dir.deleteRecursively()
            if (!staging.renameTo(dir)) {
                staging.copyRecursively(dir, overwrite = true)
                staging.deleteRecursively()
            }
            Logging.info("lanelet2: extracted scene server version {0} to {1}", version, dir.absolutePath)
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun read(loader: ClassLoader, name: String): ByteArray =
        loader.getResourceAsStream(PREFIX + name)?.use { it.readBytes() }
            ?: throw IOException("missing jar resource $PREFIX$name")

    private fun resourceLoader(): ClassLoader =
        SceneServerStore::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
}
