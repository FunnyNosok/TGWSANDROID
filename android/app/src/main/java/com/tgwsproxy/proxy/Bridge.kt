package com.tgwsproxy.proxy

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "TgProxy-Bridge"

object Bridge {

    fun bridgeWsReencrypt(
        clientInput: InputStream,
        clientOutput: OutputStream,
        ws: RawWebSocket,
        label: String,
        ctx: CryptoContext,
        dc: Int = 0,
        isMedia: Boolean = false,
        splitter: MsgSplitter? = null
    ) {
        val dcTag = "DC$dc${if (isMedia) "m" else ""}"
        val upBytes = AtomicLong(0)
        val downBytes = AtomicLong(0)
        val upPackets = AtomicLong(0)
        val downPackets = AtomicLong(0)
        val startTime = System.currentTimeMillis()
        val done = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        val tcpToWs = Thread({
            try {
                val buf = ByteArray(65536)
                while (!done.get()) {
                    val n = clientInput.read(buf)
                    if (n <= 0) {
                        if (splitter != null) {
                            val tail = splitter.flush()
                            if (tail.isNotEmpty()) ws.send(tail[0])
                        }
                        break
                    }
                    ProxyStats.bytesUp.addAndGet(n.toLong())
                    upBytes.addAndGet(n.toLong())
                    upPackets.incrementAndGet()

                    ctx.clientDecryptor.update(buf, 0, n, buf, 0)
                    ctx.tgEncryptor.update(buf, 0, n, buf, 0)

                    if (splitter != null) {
                        val parts = splitter.split(buf, 0, n)
                        if (parts.isEmpty()) continue
                        if (parts.size > 1) ws.sendBatch(parts)
                        else ws.send(parts[0])
                    } else {
                        ws.send(buf, 0, n)
                    }
                }
            } catch (_: Exception) {
            } finally {
                done.set(true)
                latch.countDown()
            }
        }, "tcp->ws-$label")

        val wsToTcp = Thread({
            try {
                while (!done.get()) {
                    val data = ws.recv() ?: break
                    ProxyStats.bytesDown.addAndGet(data.size.toLong())
                    downBytes.addAndGet(data.size.toLong())
                    downPackets.incrementAndGet()

                    ctx.tgDecryptor.update(data, 0, data.size, data, 0)
                    ctx.clientEncryptor.update(data, 0, data.size, data, 0)

                    synchronized(clientOutput) {
                        clientOutput.write(data)
                        clientOutput.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                done.set(true)
                latch.countDown()
            }
        }, "ws->tcp-$label")

        tcpToWs.start()
        wsToTcp.start()

        try {
            latch.await()
        } catch (_: InterruptedException) {}

        done.set(true)

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        Log.i(TAG, "[$label] $dcTag WS session closed: ^${Constants.humanBytes(upBytes.get())} " +
                "(${upPackets.get()} pkts) v${Constants.humanBytes(downBytes.get())} " +
                "(${downPackets.get()} pkts) in %.1fs".format(elapsed))

        try { ws.close() } catch (_: Exception) {}
        try { clientOutput.close() } catch (_: Exception) {}

        try { tcpToWs.join(2000) } catch (_: Exception) {}
        try { wsToTcp.join(2000) } catch (_: Exception) {}
    }

    fun bridgeTcpReencrypt(
        clientInput: InputStream,
        clientOutput: OutputStream,
        remoteInput: InputStream,
        remoteOutput: OutputStream,
        label: String,
        ctx: CryptoContext
    ) {
        val done = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        fun forward(src: InputStream, dst: OutputStream, isUp: Boolean, name: String): Thread {
            return Thread({
                try {
                    val buf = ByteArray(65536)
                    while (!done.get()) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        if (isUp) {
                            ProxyStats.bytesUp.addAndGet(n.toLong())
                            ctx.clientDecryptor.update(buf, 0, n, buf, 0)
                            ctx.tgEncryptor.update(buf, 0, n, buf, 0)
                        } else {
                            ProxyStats.bytesDown.addAndGet(n.toLong())
                            ctx.tgDecryptor.update(buf, 0, n, buf, 0)
                            ctx.clientEncryptor.update(buf, 0, n, buf, 0)
                        }
                        synchronized(dst) {
                            dst.write(buf, 0, n)
                            dst.flush()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    done.set(true)
                    latch.countDown()
                }
            }, name)
        }

        val up = forward(clientInput, remoteOutput, true, "tcp-up-$label")
        val down = forward(remoteInput, clientOutput, false, "tcp-down-$label")
        up.start()
        down.start()

        try { latch.await() } catch (_: InterruptedException) {}
        done.set(true)

        try { clientOutput.close() } catch (_: Exception) {}
        try { remoteOutput.close() } catch (_: Exception) {}
        try { up.join(2000) } catch (_: Exception) {}
        try { down.join(2000) } catch (_: Exception) {}
    }
}
