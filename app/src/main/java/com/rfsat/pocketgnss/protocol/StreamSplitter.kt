package com.rfsat.pocketgnss.protocol

/**
 * Splits the receiver's byte stream into NMEA lines, UBX frames and RTCM3 frames. A u-blox port can carry
 * all three at once (u-center does the same); anything else (ESP32 boot log, line noise, a baud-rate
 * mismatch) is counted as garbage so the app can tell the user and, on USB, try another baud rate.
 */
class StreamSplitter(
    private val onNmea: (String) -> Unit,
    private val onUbx: (UbxFrame) -> Unit = {},
    private val onRtcm: (RtcmFrame) -> Unit = {},
) {
    private enum class State { IDLE, NMEA, UBX_SYNC2, UBX_HEADER, UBX_BODY, RTCM_HEADER, RTCM_BODY }

    private var state = State.IDLE
    private val line = StringBuilder(128)
    private var buf = ByteArray(1100)
    private var have = 0
    private var need = 0

    var garbageBytes = 0L; private set
    var badChecksums = 0L; private set
    var nmeaLines = 0L; private set
    var ubxFrames = 0L; private set
    var rtcmFrames = 0L; private set

    fun reset() { state = State.IDLE; line.setLength(0); have = 0 }

    fun feed(data: ByteArray, len: Int = data.size) {
        for (i in 0 until len) byte(data[i].toInt() and 0xFF)
    }

    private fun byte(b: Int) {
        when (state) {
            State.IDLE -> start(b)
            State.NMEA -> when {
                b == '\n'.code || b == '\r'.code -> { finishLine(); state = State.IDLE }
                b == '$'.code -> { garbageBytes += line.length; line.setLength(0); line.append('$') }
                b < 0x20 || b > 0x7E || line.length > 200 -> { garbageBytes += line.length + 1; line.setLength(0); state = State.IDLE; if (b == Ubx.SYNC1 || b == Rtcm.PREAMBLE) start(b) }
                else -> line.append(b.toChar())
            }
            State.UBX_SYNC2 -> if (b == Ubx.SYNC2) { put(b); state = State.UBX_HEADER; need = 6 } else { garbageBytes++; state = State.IDLE; start(b) }
            State.UBX_HEADER -> {
                put(b)
                if (have == need) {
                    val len = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)
                    if (len > 4096) { garbageBytes += have; state = State.IDLE; return }
                    need = 6 + len + 2
                    ensure(need)
                    state = State.UBX_BODY
                }
            }
            State.UBX_BODY -> {
                put(b)
                if (have == need) {
                    val (a, c) = Ubx.checksum(buf, 2, need - 2)
                    if (a == (buf[need - 2].toInt() and 0xFF) && c == (buf[need - 1].toInt() and 0xFF)) {
                        ubxFrames++
                        onUbx(UbxFrame(buf[2].toInt() and 0xFF, buf[3].toInt() and 0xFF, buf.copyOfRange(6, need - 2)))
                    } else { badChecksums++ }
                    state = State.IDLE
                }
            }
            State.RTCM_HEADER -> {
                put(b)
                if (have == 3) {
                    val len = ((buf[1].toInt() and 0x03) shl 8) or (buf[2].toInt() and 0xFF)
                    if ((buf[1].toInt() and 0xFC) != 0) { garbageBytes += 3; state = State.IDLE; return }
                    need = 3 + len + 3
                    ensure(need)
                    state = State.RTCM_BODY
                }
            }
            State.RTCM_BODY -> {
                put(b)
                if (have == need) {
                    val crc = Rtcm.crc24q(buf, 0, need - 3)
                    val got = ((buf[need - 3].toInt() and 0xFF) shl 16) or ((buf[need - 2].toInt() and 0xFF) shl 8) or (buf[need - 1].toInt() and 0xFF)
                    if (crc == got) {
                        rtcmFrames++
                        val f = buf.copyOf(need)
                        onRtcm(RtcmFrame(Rtcm.type(f), Rtcm.stationId(f), f))
                    } else { badChecksums++ }
                    state = State.IDLE
                }
            }
        }
    }

    private fun start(b: Int) {
        have = 0
        when (b) {
            '$'.code, '!'.code -> { line.setLength(0); line.append(b.toChar()); state = State.NMEA }
            Ubx.SYNC1 -> { put(b); state = State.UBX_SYNC2 }
            Rtcm.PREAMBLE -> { put(b); state = State.RTCM_HEADER }
            '\r'.code, '\n'.code -> {}
            else -> garbageBytes++
        }
    }

    private fun finishLine() {
        if (line.length < 6) { garbageBytes += line.length; line.setLength(0); return }
        val s = line.toString()
        line.setLength(0)
        if (Nmea.checksumOk(s)) { nmeaLines++; onNmea(s) } else badChecksums++
    }

    private fun put(b: Int) { ensure(have + 1); buf[have++] = b.toByte() }
    private fun ensure(n: Int) { if (n > buf.size) buf = buf.copyOf(maxOf(n, buf.size * 2)) }
}
