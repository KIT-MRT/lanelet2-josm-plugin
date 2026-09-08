package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.openstreetmap.josm.tools.Logging
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Versioned extraction of the shipped 3D viewer and style_images to user-data.
 * Mirrors [org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendStore].
 */
object Viewer3dStore {
    fun shippedVersion(loader: ClassLoader = resourceLoader()): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (entry in Viewer3dResources.enumerateShippedEntries(loader)) {
            md.update(entry.resourcePath.toByteArray(Charsets.UTF_8))
            md.update(0)
            val bytes = loader.getResourceAsStream(entry.resourcePath)?.use { it.readBytes() }
                ?: throw IOException("missing jar resource ${entry.resourcePath}")
            md.update(bytes)
        }
        return HexFormat.of().formatHex(md.digest())
    }

    fun ensureExtracted(
        root: File = Viewer3dResources.defaultExtractRoot(),
        loader: ClassLoader = resourceLoader(),
    ): ExtractPaths {
        val version = shippedVersion(loader)
        val viewerDir = Viewer3dResources.viewerExtractDir(root)
        val iconsDir = Viewer3dResources.styleImagesExtractDir(root)
        val versionFile = File(viewerDir, Viewer3dResources.VERSION_FILE)
        val entries = Viewer3dResources.enumerateShippedEntries(loader)
        if (viewerDir.isDirectory &&
            iconsDir.isDirectory &&
            versionFile.isFile &&
            versionFile.readText().trim() == version &&
            entries.all { File(root, it.extractRelativePath).isFile }
        ) {
            return ExtractPaths(viewerDir, iconsDir, File(viewerDir, "server.py"))
        }
        extract(root, loader, version)
        return ExtractPaths(viewerDir, iconsDir, File(viewerDir, "server.py"))
    }

    fun extract(
        root: File = Viewer3dResources.defaultExtractRoot(),
        loader: ClassLoader = resourceLoader(),
        version: String = shippedVersion(loader),
    ) {
        val viewerDir = Viewer3dResources.viewerExtractDir(root)
        val iconsDir = Viewer3dResources.styleImagesExtractDir(root)
        val staging = File(root, ".viewer3d_extracting")
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("cannot create staging dir $staging")
        try {
            for (entry in Viewer3dResources.enumerateShippedEntries(loader)) {
                val src = loader.getResourceAsStream(entry.resourcePath)
                    ?: throw IOException("missing jar resource ${entry.resourcePath}")
                val dest = File(staging, entry.extractRelativePath)
                dest.parentFile?.mkdirs()
                src.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            root.mkdirs()
            if (viewerDir.exists()) viewerDir.deleteRecursively()
            if (iconsDir.exists()) iconsDir.deleteRecursively()
            val stagedViewer = File(staging, Viewer3dResources.EXTRACT_SUBDIR)
            val stagedIcons = File(staging, Viewer3dResources.STYLE_IMAGES_SUBDIR)
            if (!stagedViewer.renameTo(viewerDir)) {
                stagedViewer.copyRecursively(viewerDir, overwrite = true)
                stagedViewer.deleteRecursively()
            }
            if (!stagedIcons.renameTo(iconsDir)) {
                stagedIcons.copyRecursively(iconsDir, overwrite = true)
                stagedIcons.deleteRecursively()
            }
            File(viewerDir, Viewer3dResources.VERSION_FILE).writeText(version)
            staging.deleteRecursively()
            Logging.info("lanelet2: extracted 3D viewer version {0} to {1}", version, viewerDir.absolutePath)
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    data class ExtractPaths(
        val viewerDir: File,
        val iconsDir: File,
        val serverScript: File,
    )

    private fun resourceLoader(): ClassLoader =
        Viewer3dStore::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
}
