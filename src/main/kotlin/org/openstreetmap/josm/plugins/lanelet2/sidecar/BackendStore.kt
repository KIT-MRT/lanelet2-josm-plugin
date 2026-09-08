package org.openstreetmap.josm.plugins.lanelet2.sidecar

import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.tools.Logging
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Versioned extraction of shipped backend scripts to a user-data directory.
 *
 * Extracts on first use and whenever the content hash of the jar resources
 * changes. Does **not** re-extract on every launch: a `.shipped_version` file
 * next to the scripts is compared to [shippedVersion].
 *
 * The hash is a SHA-256 of the shipped files in sorted name order, so it is
 * deterministic and independent of the plugin's SNAPSHOT version string.
 */
object BackendStore {
    fun shippedVersion(loader: ClassLoader = resourceLoader()): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (name in BackendScripts.SHIPPED_FILES.sorted()) {
            md.update(name.toByteArray(Charsets.UTF_8))
            md.update(0)
            val bytes = loader.getResourceAsStream("${BackendScripts.RESOURCE_DIR}/$name")
                ?.use { it.readBytes() }
                ?: throw IOException("missing jar resource ${BackendScripts.RESOURCE_DIR}/$name")
            md.update(bytes)
        }
        return HexFormat.of().formatHex(md.digest())
    }

    /**
     * Directory that holds the extracted scripts. Override [dir] in tests.
     * Production default: `<josm-userdata>/plugins/lanelet2/backends`.
     */
    fun defaultExtractDir(): File {
        val root = try {
            Config.getDirs()?.getUserDataDirectory(true)
        } catch (_: Exception) {
            null
        } ?: File(System.getProperty("java.io.tmpdir"), "lanelet2-plugin")
        return File(File(File(root, "plugins"), "lanelet2"), BackendScripts.EXTRACT_SUBDIR)
    }

    /**
     * Directory holding the private virtualenv, by default
     * `$XDG_DATA_HOME/josm-lanelet2/venv` (`~/.local/share/...` when unset).
     *
     * Deliberately **outside** JOSM's user data directory, unlike the extracted
     * scripts. Under `runJosm` the Gradle JOSM plugin redirects user data into
     * `build/.josm`, and a venv there breaks the dev loop twice over: Gradle
     * traverses it and `initJosmPrefs` fails on `bin/python` (a relative symlink
     * to `bin/python3`), and `clean` discards a ~116 MB pip install. The
     * extracted scripts are unaffected because they are small and regenerate
     * from the jar. An explicit `backends.python` setting still wins over this.
     */
    fun defaultVenvDir(
        xdgDataHome: String? = System.getenv("XDG_DATA_HOME"),
        userHome: String? = System.getProperty("user.home"),
    ): File {
        // The XDG spec says a non-absolute XDG_DATA_HOME must be ignored.
        val base = xdgDataHome
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isAbsolute }
            ?: File(
                userHome?.takeIf { it.isNotBlank() }
                    ?: System.getProperty("java.io.tmpdir"),
                ".local/share",
            )
        return File(File(base, BackendScripts.VENV_APP_DIR), BackendScripts.VENV_SUBDIR)
    }

    /**
     * Ensure [dir] contains the current shipped scripts. Returns [dir].
     * No-op when the version file already matches [shippedVersion] and every
     * shipped file is present.
     */
    fun ensureExtracted(
        dir: File = defaultExtractDir(),
        loader: ClassLoader = resourceLoader(),
    ): File {
        val version = shippedVersion(loader)
        val versionFile = File(dir, BackendScripts.VERSION_FILE)
        if (dir.isDirectory &&
            versionFile.isFile &&
            versionFile.readText().trim() == version &&
            BackendScripts.SHIPPED_FILES.all { File(dir, it).isFile }
        ) {
            return dir
        }
        extract(dir, loader, version)
        return dir
    }

    fun extract(
        dir: File,
        loader: ClassLoader = resourceLoader(),
        version: String = shippedVersion(loader),
    ) {
        val staging = File(dir.parentFile, dir.name + ".extracting")
        if (staging.exists()) {
            staging.deleteRecursively()
        }
        if (!staging.mkdirs()) {
            throw IOException("cannot create staging dir $staging")
        }
        try {
            for (name in BackendScripts.SHIPPED_FILES) {
                val src = loader.getResourceAsStream("${BackendScripts.RESOURCE_DIR}/$name")
                    ?: throw IOException("missing jar resource ${BackendScripts.RESOURCE_DIR}/$name")
                src.use { input ->
                    val dest = File(staging, name)
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            File(staging, BackendScripts.VERSION_FILE).writeText(version)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            dir.parentFile?.mkdirs()
            try {
                Files.move(staging.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                if (!staging.renameTo(dir)) {
                    throw IOException("failed to move extracted backends into $dir")
                }
            }
            Logging.info("lanelet2: extracted backends version {0} to {1}", version, dir.absolutePath)
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    fun resolveScript(name: String, dir: File = defaultExtractDir()): File? {
        val file = File(dir, if (name.endsWith(".py")) name else "$name.py")
        return if (file.isFile) file else null
    }

    private fun resourceLoader(): ClassLoader =
        BackendStore::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
}
