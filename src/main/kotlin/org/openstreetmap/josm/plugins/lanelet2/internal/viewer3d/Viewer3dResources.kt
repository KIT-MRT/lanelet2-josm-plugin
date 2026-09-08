package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.spi.preferences.Config
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.util.jar.JarFile

/**
 * Jar resource paths for the shipped 3D viewer and style_images.
 *
 * The file list is discovered at runtime (sorted) so we do not maintain 240+
 * icon paths by hand. The content hash covers every shipped byte.
 */
object Viewer3dResources {
    const val VIEWER3D_PREFIX = "lanelet2/viewer3d/"
    const val STYLE_IMAGES_PREFIX = "lanelet2/style_images/"
    const val VERSION_FILE = ".shipped_version"
    const val EXTRACT_SUBDIR = "viewer3d"
    const val STYLE_IMAGES_SUBDIR = "style_images"

    /** Resource path in the jar → path relative to the plugin user-data root. */
    data class ShippedEntry(val resourcePath: String, val extractRelativePath: String)

    fun enumerateShippedEntries(loader: ClassLoader = resourceLoader()): List<ShippedEntry> {
        val anchor = loader.getResource("${VIEWER3D_PREFIX}server.py")
            ?: throw IllegalStateException("missing jar resource ${VIEWER3D_PREFIX}server.py")
        val entries = linkedSetOf<ShippedEntry>()
        when (anchor.protocol) {
            "jar" -> entries.addAll(scanJar(anchor))
            "file" -> entries.addAll(scanDirectory(File(anchor.toURI())))
            else -> throw IllegalStateException("unsupported resource protocol: ${anchor.protocol}")
        }
        return entries.sortedBy { it.resourcePath }
    }

    fun defaultExtractRoot(): File {
        val root: File = try {
            Config.getDirs()?.getUserDataDirectory(true)
        } catch (_: Exception) {
            null
        } ?: File(System.getProperty("java.io.tmpdir"), "lanelet2-plugin")
        return File(File(root, "plugins"), "lanelet2")
    }

    fun viewerExtractDir(root: File = defaultExtractRoot()): File =
        File(root, EXTRACT_SUBDIR)

    fun styleImagesExtractDir(root: File = defaultExtractRoot()): File =
        File(root, STYLE_IMAGES_SUBDIR)

    private fun scanJar(anchor: URL): List<ShippedEntry> {
        val conn = anchor.openConnection() as JarURLConnection
        // Do not close this JarFile: JarURLConnection caches it on the plugin
        // jar, and closing it breaks later getResourceAsStream calls, which is
        // how a re-extract can delete viewer3d/ and then fail to write static/.
        val jar: JarFile = conn.jarFile
        return jar.entries().asSequence()
            .filter { !it.isDirectory }
            .map { it.name }
            .filter { path ->
                path.startsWith(VIEWER3D_PREFIX) || path.startsWith(STYLE_IMAGES_PREFIX)
            }
            .map { path -> ShippedEntry(path, toExtractRelative(path)) }
            .toList()
    }

    private fun scanDirectory(anchorFile: File): List<ShippedEntry> {
        // build/resources/main/lanelet2/viewer3d/server.py → .../lanelet2/
        val lanelet2Root = anchorFile.parentFile?.parentFile
            ?: throw IllegalStateException("cannot infer lanelet2 resource root from $anchorFile")
        val out = ArrayList<ShippedEntry>()
        fun walk(base: File, prefix: String, relPrefix: String) {
            if (!base.isDirectory) return
            for (f in base.listFiles()?.sortedBy { it.name }.orEmpty()) {
                if (f.isDirectory) {
                    walk(f, "$prefix${f.name}/", "$relPrefix${f.name}/")
                } else {
                    out.add(ShippedEntry(prefix + f.name, relPrefix + f.name))
                }
            }
        }
        walk(File(lanelet2Root, "viewer3d"), VIEWER3D_PREFIX, "$EXTRACT_SUBDIR/")
        walk(File(lanelet2Root, "style_images"), STYLE_IMAGES_PREFIX, "$STYLE_IMAGES_SUBDIR/")
        return out
    }

    private fun toExtractRelative(resourcePath: String): String = when {
        resourcePath.startsWith(VIEWER3D_PREFIX) ->
            EXTRACT_SUBDIR + "/" + resourcePath.removePrefix(VIEWER3D_PREFIX)
        resourcePath.startsWith(STYLE_IMAGES_PREFIX) ->
            STYLE_IMAGES_SUBDIR + "/" + resourcePath.removePrefix(STYLE_IMAGES_PREFIX)
        else -> throw IllegalArgumentException(resourcePath)
    }

    private fun resourceLoader(): ClassLoader =
        Viewer3dResources::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
}
