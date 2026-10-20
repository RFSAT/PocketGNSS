package com.rfsat.pocketgnss.link

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/**
 * USB-OTG serial link (usb-serial-for-android drivers). The RFSAT receiver has three micro-USB ports, one per
 * stacked module (D3.3 §4.6.1, §4.7.1): upper = ESP32-DevKitC, the data / COM port with NMEA at 115200 bd;
 * middle = BG95, power (5 V, 1 A); bottom = ZED-F9x's own CDC-ACM port, not normally used, but the one that takes
 * UBX configuration directly (as u-center does in D3.3 §4.7.22). Both data-capable ports are supported.
 */
class UsbSerialLink private constructor(
    private val port: UsbSerialPort,
    override val info: LinkInfo,
) : GnssLink {

    override fun read(buffer: ByteArray): Int = try {
        port.read(buffer, 200)
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e.message ?: "USB read failed", e)
    }

    override fun write(data: ByteArray) {
        try { port.write(data, 1000) } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException(e.message ?: "USB write failed", e) }
    }

    override fun setBaud(baud: Int) {
        try { port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) } catch (_: Exception) {}
    }

    override fun close() { try { port.close() } catch (_: Exception) {} }

    companion object {
        /** u-blox receivers whose CDC-ACM interface the default prober may not list (M8 0x01A8, F9 0x01A9). */
        private val ubloxTable = ProbeTable().apply {
            addProduct(0x1546, 0x01A9, CdcAcmSerialDriver::class.java)
            addProduct(0x1546, 0x01A8, CdcAcmSerialDriver::class.java)
            addProduct(0x1546, 0x01A7, CdcAcmSerialDriver::class.java)
        }

        fun isCandidate(d: UsbDevice) = d.vendorId in UsbChip.gnssVendorIds || hasCdc(d)

        private fun hasCdc(d: UsbDevice) = (0 until d.interfaceCount).any {
            d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_COMM || d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
        }

        fun driverFor(d: UsbDevice): UsbSerialDriver? =
            UsbSerialProber.getDefaultProber().probeDevice(d)
                ?: UsbSerialProber(ubloxTable).probeDevice(d)
                ?: if (hasCdc(d)) CdcAcmSerialDriver(d) else null

        @Throws(IOException::class)
        fun open(manager: UsbManager, device: UsbDevice, baud: Int): UsbSerialLink {
            if (!manager.hasPermission(device)) throw IOException("No USB permission for ${device.deviceName}")
            val driver = driverFor(device) ?: throw IOException("No serial driver for %04X:%04X".format(device.vendorId, device.productId))
            val port = driver.ports.firstOrNull() ?: throw IOException("No serial port on the device")
            val conn = manager.openDevice(device) ?: throw IOException("openDevice() returned null")
            try {
                port.open(conn)
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (e: Exception) {
                try { port.close() } catch (_: Exception) {}
                throw IOException("Could not open the serial port: ${e.message}", e)
            }
            val chip = UsbChip.of(device.vendorId)
            // On the ESP32-DevKitC, RTS drives EN and DTR drives IO0: asserting RTS alone holds the board in reset.
            // Those lines are left alone; the u-blox CDC port gets DTR, which some hosts need before data flows.
            if (chip == UsbChip.UBLOX) try { port.setDTR(true) } catch (_: Exception) {}
            val name = when (chip) {
                UsbChip.UBLOX -> "u-blox ZED-F9x (bottom USB)"
                UsbChip.OTHER -> device.productName ?: "USB serial"
                UsbChip.CP210X, UsbChip.CH34X -> "RFSAT receiver (USB data port)"
                else -> (device.productName ?: chip.label) + " (USB)"
            }
            val detail = "%s · %04X:%04X%s".format(chip.label, device.vendorId, device.productId,
                if (chip == UsbChip.UBLOX) "" else " · $baud bd")
            return UsbSerialLink(port, LinkInfo(LinkKind.USB, name, detail, chip, baud))
        }
    }
}
