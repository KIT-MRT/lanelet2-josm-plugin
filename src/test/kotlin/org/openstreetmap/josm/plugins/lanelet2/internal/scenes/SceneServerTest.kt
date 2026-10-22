package org.openstreetmap.josm.plugins.lanelet2.internal.scenes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendResult
import org.openstreetmap.josm.plugins.lanelet2.sidecar.BackendSetup
import org.openstreetmap.josm.spi.preferences.Config
import org.openstreetmap.josm.spi.preferences.MemoryPreferences
import java.io.File
import java.nio.file.Path

/** The scene server from the jar: shipped files, the command, the process, the setup. */
class SceneServerTest {
    @BeforeEach
    fun setUp() {
        Config.setPreferencesInstance(MemoryPreferences())
    }

    // ---- shipped and extracted ------------------------------------------------------------
    @Test
    fun theWholePackageShipsAndNothingElse() {
        val files = SceneServerStore.shippedFiles()
        for (name in listOf("__init__.py", "__main__.py", "city.py", "scene.py", "http.py", "describe.py")) {
            assertTrue("ll2_scene/$name" in files, "ll2_scene/$name must ship: $files")
        }
        assertTrue(files.all { it.startsWith("ll2_scene/") && it.endsWith(".py") }, "$files")
        assertFalse(files.any { "__pycache__" in it || "/tests/" in it }, "$files")
    }

    @Test
    fun extractsOnceAndAgainOnlyWhenTheContentChanges(@TempDir tmp: Path) {
        val dir = File(tmp.toFile(), "scene_server")
        SceneServerStore.ensureExtracted(dir)
        val main = File(dir, "ll2_scene/__main__.py")
        assertTrue(main.isFile)
        assertEquals(SceneServerStore.shippedVersion(), File(dir, SceneServerStore.VERSION_FILE).readText())
        main.setLastModified(1_000_000L)
        SceneServerStore.ensureExtracted(dir)
        assertEquals(1_000_000L, main.lastModified(), "an up-to-date extract is left alone")
        File(dir, SceneServerStore.VERSION_FILE).writeText("old build")
        SceneServerStore.ensureExtracted(dir)
        assertNotEquals(1_000_000L, main.lastModified(), "another build's extract is replaced")
        assertFalse(File(tmp.toFile(), "scene_server.extracting").exists())
    }

    // ---- the command ------------------------------------------------------------------------
    private fun plan(root: File, mode: SceneMode, josm: Boolean) = LaunchPlan(
        root = root, mode = mode, useJosmMap = josm,
        viewerHost = "127.0.0.1", viewerPort = 8765, ingestPort = 8766, httpPort = 8767,
    )

    @Test
    fun aFolderWithPosesIsOneSceneAnythingElseACity(@TempDir tmp: Path) {
        val scene = File(tmp.toFile(), "c34c778f").apply { mkdirs() }
        File(scene, "poses.txt").writeText("# t x y z qx qy qz qw\n")
        assertEquals(SceneMode.SCENE, SceneLaunch.detectMode(scene))
        assertEquals(SceneMode.CITY, SceneLaunch.detectMode(tmp.toFile()))
    }

    @Test
    fun onJosmsMapTheServerWaitsForItAndSendsNone() {
        val args = SceneLaunch.arguments(plan(File("/data/kitscenes"), SceneMode.CITY, josm = true))
        assertEquals(
            listOf("-m", "ll2_scene", "--city", "/data/kitscenes",
                "--viewer-host", "127.0.0.1", "--viewer-port", "8765", "--ingest-port", "8766", "--http-port", "8767",
                "--no-map", "--wait-for-map", "180"),
            args,
        )
    }

    @Test
    fun aRescanReadsEverySceneAgainButOnlyACityHasAnIndex() {
        val city = plan(File("/data/kitscenes"), SceneMode.CITY, josm = true).copy(rescan = true)
        assertEquals("--rescan", SceneLaunch.arguments(city).last())
        assertFalse("--rescan" in SceneLaunch.arguments(city.copy(rescan = false)), "only when asked")
        val scene = plan(File("/data/val/abc"), SceneMode.SCENE, josm = true).copy(rescan = true)
        assertFalse("--rescan" in SceneLaunch.arguments(scene), "one scene has no index")
    }

    @Test
    fun theFrameCacheLimitIsPassedAndDefaultsByFreeSpace() {
        val args = SceneLaunch.arguments(plan(File("/data"), SceneMode.CITY, josm = false).copy(cacheLimitGb = 50))
        assertEquals(listOf("--cache-limit-gb", "50"), args.takeLast(2))
        assertFalse("--cache-limit-gb" in SceneLaunch.arguments(plan(File("/data"), SceneMode.CITY, josm = false)),
            "none set: the server's own default")
        assertEquals(50, SceneLaunch.defaultCacheLimitGb(363L * 1000 * 1000 * 1000))
        assertEquals(10, SceneLaunch.defaultCacheLimitGb(120L * 1000 * 1000 * 1000), "a fuller disk keeps 10")
        assertEquals(File("/x/josm-lanelet2/scene-server"), SceneLaunch.cacheDir(xdgCacheHome = "/x", home = "/h"))
        assertEquals(File("/h/.cache/josm-lanelet2/scene-server"), SceneLaunch.cacheDir(xdgCacheHome = null, home = "/h"))
        assertTrue(SceneLaunch.freeBytes(File("/no/such/dir/below/root")) > 0, "measured on the nearest existing parent")
    }

    @Test
    fun withoutJosmsMapASceneBringsItsOwn() {
        val args = SceneLaunch.arguments(plan(File("/data/val/abc"), SceneMode.SCENE, josm = false))
        assertEquals(listOf("-m", "ll2_scene", "--scene", "/data/val/abc"), args.take(4))
        assertFalse("--no-map" in args || "--wait-for-map" in args, "$args")
    }

    @Test
    fun anEnvScriptIsSourcedFirst(@TempDir tmp: Path) {
        val env = File(tmp.toFile(), "setup.bash").apply { writeText("export X=1\n") }
        assertEquals(listOf("py", "-m", "ll2_scene"), SceneLaunch.wrap("py", listOf("-m", "ll2_scene"), null))
        assertEquals(listOf("py", "-c", "x"), SceneLaunch.wrap("py", listOf("-c", "x"), "/no/such/script"))
        val wrapped = SceneLaunch.wrap("/v/bin/python", listOf("-m", "ll2_scene", "--city", "/a b"), env.path)
        assertEquals(listOf("bash", "-c"), wrapped.take(2))
        assertEquals("source '${env.path}' && '/v/bin/python' '-m' 'll2_scene' '--city' '/a b'", wrapped[2])
    }

    // ---- the process ------------------------------------------------------------------------
    /** A stand-in "python" that logs its arguments and PYTHONPATH, then stays up (or exits at once). */
    private fun fakePython(dir: File, stayUp: Boolean): File = File(dir, "python").apply {
        writeText("#!/bin/sh\necho \"args: \$*\"\necho \"PYTHONPATH=\$PYTHONPATH\"\n" +
            (if (stayUp) "exec sleep 30\n" else "echo 'ModuleNotFoundError: No module named kitscenes'\nexit 1\n"))
        setExecutable(true)
    }

    @Test
    fun startsLogsAndStops(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        val extract = File(dir, "scene_server").apply { mkdirs() }
        val server = SceneServerProcess(logFile = File(dir, "scene_server.log"), sleeper = {})
        val (ok, msg) = server.start(plan(File("/data/kitscenes"), SceneMode.CITY, josm = true),
            fakePython(dir, stayUp = true).path, extract, null)
        assertTrue(ok, msg)
        assertTrue(server.isAlive())
        val deadline = System.currentTimeMillis() + 5000
        while (server.tail().size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val log = server.tail()
        assertTrue(log.any { it.startsWith("args: -m ll2_scene --city /data/kitscenes") }, "$log")
        assertTrue(log.any { it.startsWith("PYTHONPATH=${extract.absolutePath}") }, "the package is importable: $log")
        assertTrue(server.statusText().startsWith("Running: city kitscenes"), server.statusText())
        server.stop()
        assertFalse(server.isAlive())
        assertEquals("Stopped.", server.statusText())
    }

    @Test
    fun anImmediateExitIsReportedWithTheLog(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        val server = SceneServerProcess(logFile = File(dir, "scene_server.log"), sleeper = { Thread.sleep(it) })
        val (ok, msg) = server.start(plan(File("/data"), SceneMode.CITY, josm = false),
            fakePython(dir, stayUp = false).path, dir, null)
        assertFalse(ok)
        assertTrue(msg.contains("ModuleNotFoundError"), msg)
        assertFalse(server.isAlive())
    }

    @Test
    fun foreignServersAreOnlyThoseOnThePort() {
        assertTrue(SceneServerProcess(logFile = File("/nonexistent/log")).foreignServers(1).isEmpty())
    }

    // ---- setup ------------------------------------------------------------------------------
    @Test
    fun kitscenesComesFromAPinnedArchiveWithoutItsExtras() {
        val cmd = SceneSetup.kitscenesCommand("/v/bin/python")
        assertEquals(listOf("/v/bin/python", "-m", "pip", "install", "--no-deps"), cmd.take(5))
        assertTrue(cmd[5].contains("/archive/${SceneSetup.KITSCENES_COMMIT}.zip"), cmd[5])
        assertEquals(40, SceneSetup.KITSCENES_COMMIT.length, "a full commit, not a branch")
        val deps = SceneSetup.depsCommand("/v/bin/python")
        assertTrue(deps.containsAll(listOf("numpy<2", "pyarrow==17.0.0", "scipy<1.15")), "$deps")
    }

    @Test
    fun theCheckRunsTheImportsAndSaysWhatIsMissing() {
        var ran: List<String>? = null
        val ok = SceneSetup.check("python3") { cmd, _ -> ran = cmd; BackendResult.ok("ok\n") }
        assertTrue(ok.ok)
        assertEquals(listOf("python3", "-c", SceneSetup.IMPORT_CHECK), ran)
        val missing = SceneSetup.check("python3") { _, _ ->
            BackendResult.fail("exit 1", stderr = "Traceback\nModuleNotFoundError: No module named 'kitscenes'\n")
        }
        assertFalse(missing.ok)
        assertTrue(missing.message.contains("No module named 'kitscenes'"), missing.message)
    }

    private fun interpreter(ok: Boolean, python: String = "") =
        BackendSetup.InterpreterCheck(ok = ok, message = "", python = python)

    @Test
    fun aPythonOnPathIsPreferred() {
        val found = SceneSetup.findBootstrapPython(
            log = {}, check = { interpreter(it == "python3.11", it) }, uv = { error("uv not needed") },
        )
        assertEquals("python3.11", found)
    }

    @Test
    fun withoutOneAndWithoutUvItSaysWhatToInstall() {
        val logs = ArrayList<String>()
        assertNull(SceneSetup.findBootstrapPython(log = { logs.add(it) }, check = { interpreter(false) }, uv = { null }))
        assertTrue(logs.single().contains("python3.12") && logs.single().contains("uv"), "$logs")
    }

    @Test
    fun uvProvidesAPython312() {
        val ran = ArrayList<List<String>>()
        var installed = false
        val found = SceneSetup.findBootstrapPython(
            log = {}, check = { interpreter(false) }, uv = { "/home/u/.local/bin/uv" },
            run = { cmd, _ -> ran.add(cmd); installed = true; 0 },
            capture = { if (installed) "/home/u/.local/share/uv/python/cpython-3.12/bin/python3.12" else null },
        )
        assertEquals("/home/u/.local/share/uv/python/cpython-3.12/bin/python3.12", found)
        assertEquals(listOf(listOf("/home/u/.local/bin/uv", "python", "install", "3.12")), ran)
    }

    @Test
    fun aHealthyVirtualenvOnlyGetsThePackages() {
        val ran = ArrayList<List<String>>()
        val result = SceneSetup.install(
            log = {}, run = { cmd, _ -> ran.add(cmd); 0 }, healthy = { true },
            bootstrap = { error("no venv needed") }, verify = { SceneSetup.Check(true, "ready") },
        )
        assertNull(result.error)
        assertEquals(2, ran.size)
        assertEquals(listOf("python3", "-m", "pip", "install"), ran[0].take(4))
        assertTrue("--no-deps" in ran[1])
    }

    @Test
    fun withoutOneTheVirtualenvComesFirst(@TempDir tmp: Path) {
        val venv = File(tmp.toFile(), "venv")
        val ran = ArrayList<List<String>>()
        val result = SceneSetup.install(
            log = {},
            run = { cmd, _ ->
                ran.add(cmd)
                if (cmd.drop(1).take(2) == listOf("-m", "venv")) {
                    File(venv, "bin/python").apply { parentFile.mkdirs(); writeText("") }
                }
                0
            },
            healthy = { false }, bootstrap = { "/usr/bin/python3.12" }, backendsDir = { tmp.toFile() },
            verify = { SceneSetup.Check(true, "ready") }, venvDir = venv,
        )
        assertNull(result.error)
        val venvPython = File(venv, "bin/python").absolutePath
        assertEquals(venvPython, result.python)
        assertEquals(listOf("/usr/bin/python3.12", "-m", "venv", venv.absolutePath), ran.first())
        assertEquals(venvPython, ran.last().first(), "the packages go into the new virtualenv")
        assertTrue(ran.any { it.containsAll(listOf("lanelet2", "numpy<2")) }, "the backends' own install: $ran")
    }

    /**
     * Registered as the plugin does. The viewer is itself inserted (after the
     * zoom filter) and insertions do not chain, so anchoring on the viewer's
     * id silently dropped the entry: street imagery was never in the menu.
     */
    @Test
    fun theActionSitsNextToTheViewerAsStreetImageryDoes() {
        val registry = org.openstreetmap.josm.plugins.lanelet2.platform.ActionRegistry()
        org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d.Viewer3dActions.registerAll(registry)
        org.openstreetmap.josm.plugins.lanelet2.tools.StreetImagery.registerAll(registry)
        SceneActions.registerAll(registry)
        val ids = registry.build(org.openstreetmap.josm.plugins.lanelet2.platform.MenuId.UTILS).map { it?.id }
        val viewer = ids.indexOf("ll2_viewer3d_window")
        assertTrue(viewer >= 0, "$ids")
        assertEquals(listOf("ll2_street_imagery", SceneActions.SLOT_ID), ids.subList(viewer + 1, viewer + 3), "$ids")
        assertEquals(1, ids.count { it == SceneActions.SLOT_ID })
    }
}
