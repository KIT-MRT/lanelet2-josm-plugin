package org.openstreetmap.josm.plugins.lanelet2.internal.viewer3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Viewer3dSocketClientTest {

    private fun patch() = OutboundMessage.Patch(emptyList())

    /**
     * Regression: `requestResync` used to call `onConnected`, whose real
     * handler ([Viewer3dHook.requestResync]) calls straight back into
     * `requestResync`. That recursion pinned the EDT near 85% CPU, drained the
     * queue on every pass and perpetually restarted the send debounce, so the
     * viewer reported a healthy connection while never receiving a feature.
     */
    @Test
    fun requestResyncDoesNotCallBackIntoTheOwner() {
        val calls = AtomicInteger()
        lateinit var client: Viewer3dSocketClient
        client = Viewer3dSocketClient(
            host = { "127.0.0.1" },
            port = { 1 },
            onCommand = {},
            onConnected = {
                // Mirrors what Viewer3dHook.requestResync does. If the client
                // calls back, this recurses until the stack or the test dies.
                if (calls.incrementAndGet() < 1000) client.requestResync()
            },
        )

        client.requestResync()

        assertEquals(0, calls.get(), "requestResync must not invoke onConnected")
    }

    @Test
    fun queueOverflowAsksTheOwnerForAFreshSnapshot() {
        val resyncs = AtomicInteger()
        val client = Viewer3dSocketClient(
            host = { "127.0.0.1" },
            port = { 1 },
            onCommand = {},
            onConnected = { resyncs.incrementAndGet() },
            queueMax = 4,
        )

        repeat(4) { client.enqueue(patch()) }
        assertEquals(0, resyncs.get(), "a queue that still fits must not resync")

        client.enqueue(patch())
        assertEquals(1, resyncs.get(), "overflow must ask for a fresh snapshot")
    }

    @Test
    fun requestResyncDropsTheBacklog() {
        val resyncs = AtomicInteger()
        val client = Viewer3dSocketClient(
            host = { "127.0.0.1" },
            port = { 1 },
            onCommand = {},
            onConnected = { resyncs.incrementAndGet() },
            queueMax = 4,
        )

        repeat(4) { client.enqueue(patch()) }
        client.requestResync()

        // The queue was full; if the drain worked this one fits without overflow.
        client.enqueue(patch())
        assertEquals(0, resyncs.get(), "requestResync should have emptied the queue")
    }

    @Test
    fun connectingNotifiesTheOwnerOncePerConnection() {
        val connected = CountDownLatch(1)
        val calls = AtomicInteger()
        ServerSocket(0).use { server ->
            val client = Viewer3dSocketClient(
                host = { "127.0.0.1" },
                port = { server.localPort },
                onCommand = {},
                onConnected = {
                    calls.incrementAndGet()
                    connected.countDown()
                },
            )
            client.start()
            try {
                server.accept().use {
                    assertTrue(connected.await(5, TimeUnit.SECONDS), "sender never connected")
                    Thread.sleep(300)
                    assertEquals(1, calls.get(), "one connection must notify the owner once")
                }
            } finally {
                client.stop()
            }
        }
    }
}
