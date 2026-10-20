package com.rfsat.pocketgnss.link

import com.rfsat.pocketgnss.protocol.StreamSplitter
import com.rfsat.pocketgnss.sim.SimConfig
import com.rfsat.pocketgnss.sim.SimReceiver
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** [GnssLink] backed by [SimReceiver]: NMEA at the configured rate, replies to UBX, RTCM accepted. */
class SimulatedLink(config: SimConfig) : GnssLink {
    val receiver = SimReceiver(config, seed = System.nanoTime())
    private val pending = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    private var nextEpochNs = System.nanoTime() + 300_000_000L
    private val inbound = StreamSplitter(onNmea = {}, onUbx = { f -> receiver.handleUbx(f).forEach { pending.add(it) } },
        onRtcm = { receiver.rtcmReceived() })

    override val info = LinkInfo(LinkKind.SIMULATOR, "Simulated RTK receiver", "ZED-F9P model · synthetic NMEA")

    override fun read(buffer: ByteArray): Int {
        if (closed) throw IOException("closed")
        pending.poll()?.let { it.copyInto(buffer, 0, 0, minOf(it.size, buffer.size)); return minOf(it.size, buffer.size) }
        val wait = nextEpochNs - System.nanoTime()
        if (wait > 0) {
            val reply = pending.poll(minOf(wait, 200_000_000L), TimeUnit.NANOSECONDS)
            if (reply != null) { reply.copyInto(buffer, 0, 0, minOf(reply.size, buffer.size)); return minOf(reply.size, buffer.size) }
            if (nextEpochNs - System.nanoTime() > 0) return 0
        }
        nextEpochNs += receiver.periodMs * 1_000_000L
        if (nextEpochNs < System.nanoTime()) nextEpochNs = System.nanoTime() + receiver.periodMs * 1_000_000L
        val bytes = synchronized(receiver) { receiver.epoch() }.joinToString("") { it + "\r\n" }.toByteArray(Charsets.US_ASCII)
        // Long epochs (many GSV lines) are handed over in buffer-sized pieces.
        if (bytes.size <= buffer.size) { bytes.copyInto(buffer); return bytes.size }
        bytes.copyInto(buffer, 0, 0, buffer.size)
        var i = buffer.size
        while (i < bytes.size) { pending.add(bytes.copyOfRange(i, minOf(bytes.size, i + buffer.size))); i += buffer.size }
        return buffer.size
    }

    override fun write(data: ByteArray) {
        if (closed) throw IOException("closed")
        synchronized(receiver) { inbound.feed(data) }
    }

    fun configure(c: SimConfig) = synchronized(receiver) { receiver.configure(c) }

    override fun close() { closed = true }
}
