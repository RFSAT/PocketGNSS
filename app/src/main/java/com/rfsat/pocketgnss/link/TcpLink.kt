package com.rfsat.pocketgnss.link

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * NMEA over TCP: the receiver's Wi-Fi TCP stream (ESPrtk Stream Manager), an EOS Tools Pro style relay
 * (port 3967, D3.3 §4.3), or any NMEA server on the network.
 */
class TcpLink private constructor(private val socket: Socket, override val info: LinkInfo) : GnssLink {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun read(buffer: ByteArray): Int = try {
        val n = input.read(buffer)
        if (n < 0) throw IOException("Connection closed by ${info.name}")
        n
    } catch (_: SocketTimeoutException) { 0 }

    override fun write(data: ByteArray) { output.write(data); output.flush() }

    override fun close() { try { socket.close() } catch (_: Exception) {} }

    companion object {
        @Throws(IOException::class)
        fun open(host: String, port: Int): TcpLink {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), 6000)
                s.soTimeout = 300
                s.tcpNoDelay = true
            } catch (e: IOException) {
                try { s.close() } catch (_: Exception) {}
                throw IOException("Could not connect to $host:$port: ${e.message}", e)
            }
            return TcpLink(s, LinkInfo(LinkKind.TCP, "$host:$port", "NMEA over TCP"))
        }
    }
}
