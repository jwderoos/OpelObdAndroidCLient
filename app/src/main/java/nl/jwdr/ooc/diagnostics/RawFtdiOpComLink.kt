package nl.jwdr.ooc.diagnostics

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.jwdr.ooc.transport.opcom.OpComLink

/**
 * Experimental [OpComLink] that talks to the OP-COM clone's FTDI chip with
 * raw [UsbDeviceConnection] transfers instead of `usb-serial-for-android`,
 * so the host-side USB sequence can be made **byte-identical to what the
 * vendor software does** (taken from a USB packet capture of `OP-COM.exe`;
 * see docs/opcom-handshake-handover.md, "Update 2026-08-25").
 *
 * Differences from [UsbSerialOpComLink] that this deliberately reproduces:
 * five `RESET` + `GET_MODEM_STATUS` pairs instead of one reset; **no**
 * `SET_DATA` line-config request; **no** DTR/RTS de-assert at open (the
 * library bakes one into its `open()`); RTS/DTR asserted a second time after
 * purging; a ~0.9 s pause before the first command; and every write split
 * into one bulk transfer per byte. Purpose: find out whether any of those
 * is what makes the clone answer `AB` with `7F` instead of `EB`. Once the
 * culprit is known this should collapse back into one link implementation.
 */
class RawFtdiOpComLink(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val verboseLogging: () -> Boolean = { false },
) : OpComLink {

    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val conn = usbManager.openDevice(device)
                ?: throw IOException("failed to open ${device.deviceName} — USB permission not granted?")
            val intf = device.getInterface(0)
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                throw IOException("could not claim interface 0 of ${device.deviceName}")
            }
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            connection = conn
            iface = intf
            inEndpoint = epIn ?: throw IOException("no bulk IN endpoint")
            outEndpoint = epOut ?: throw IOException("no bulk OUT endpoint")
            log("open: interface claimed, inEp=${epIn.address} outEp=${epOut.address} maxPacket=${epIn.maxPacketSize}")

            // --- vendor init sequence, verbatim from the OP-COM.exe USB capture ---
            repeat(RESET_COUNT) {
                control(conn, REQ_RESET, SIO_RESET_ALL, "RESET")
                val status = ByteArray(2)
                val n = conn.controlTransfer(0xC0, REQ_GET_MODEM_STATUS, 0, 0, status, 2, CONTROL_TIMEOUT_MS)
                log("ctrl GET_MODEM_STATUS -> n=$n [${status.toHex()}]")
            }
            control(conn, REQ_SET_BAUD_RATE, BAUD_DIVISOR_500000, "SET_BAUD_RATE(500000)")
            control(conn, REQ_SET_LATENCY_TIMER, LATENCY_TIMER_MS, "SET_LATENCY_TIMER")
            control(conn, REQ_MODEM_CTRL, MODEM_RTS_ENABLE, "MODEM_CTRL RTS=1")
            control(conn, REQ_MODEM_CTRL, MODEM_DTR_ENABLE, "MODEM_CTRL DTR=1")
            repeat(PURGE_TX_COUNT) { control(conn, REQ_RESET, SIO_RESET_PURGE_1, "PURGE(1)") }
            control(conn, REQ_RESET, SIO_RESET_PURGE_2, "PURGE(2)")
            delay(POST_PURGE_DELAY_MS)
            control(conn, REQ_MODEM_CTRL, MODEM_RTS_ENABLE, "MODEM_CTRL RTS=1 (again)")
            control(conn, REQ_MODEM_CTRL, MODEM_DTR_ENABLE, "MODEM_CTRL DTR=1 (again)")
            delay(PRE_COMMAND_DELAY_MS)
            log("open: init sequence done, ready for first command")
        }
    }

    private fun control(conn: UsbDeviceConnection, request: Int, value: Int, label: String) {
        val r = conn.controlTransfer(0x40, request, value, 0, null, 0, CONTROL_TIMEOUT_MS)
        log("ctrl $label bReq=0x%02x wVal=0x%04x -> %d".format(request, value, r))
        if (r < 0) throw IOException("$label failed: result=$r")
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            val conn = connection
            connection = null
            runCatching { iface?.let { conn?.releaseInterface(it) } }
            runCatching { conn?.close() }
            iface = null
            inEndpoint = null
            outEndpoint = null
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val conn = connection ?: throw IOException("link is not open")
            val ep = outEndpoint ?: throw IOException("link is not open")
            log("write [${data.toHex()}] as ${data.size} single-byte transfers")
            for (b in data) {
                val r = conn.bulkTransfer(ep, byteArrayOf(b), 1, WRITE_TIMEOUT_MS)
                if (r != 1) throw IOException("bulk OUT failed: result=$r")
            }
        }
    }

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        val ep = inEndpoint ?: throw IOException("link is not open")
        // One USB packet per transfer so the FTDI 2-byte modem-status header
        // sits at offset 0 exactly once; idle status-only packets are skipped.
        val buffer = ByteArray(ep.maxPacketSize)
        while (true) {
            val conn = connection ?: throw IOException("link closed")
            val n = conn.bulkTransfer(ep, buffer, buffer.size, READ_TIMEOUT_MS)
            if (n < 0) {
                if (connection == null) throw IOException("link closed")
                continue // timeout, nothing received
            }
            if (n > FTDI_HEADER_LENGTH) {
                val payload = buffer.copyOfRange(FTDI_HEADER_LENGTH, n)
                log("read hdr=[${buffer.copyOf(FTDI_HEADER_LENGTH).toHex()}] [${payload.toHex()}]")
                return@withContext payload
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    private fun log(msg: String) {
        if (verboseLogging()) Log.i(TAG, msg)
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02x".format(it) }

    companion object {
        private const val TAG = "RawFtdiOpComLink"

        private const val REQ_RESET = 0x00
        private const val REQ_MODEM_CTRL = 0x01
        private const val REQ_SET_BAUD_RATE = 0x03
        private const val REQ_GET_MODEM_STATUS = 0x05
        private const val REQ_SET_LATENCY_TIMER = 0x09

        private const val SIO_RESET_ALL = 0
        private const val SIO_RESET_PURGE_1 = 1
        private const val SIO_RESET_PURGE_2 = 2
        private const val MODEM_DTR_ENABLE = 0x0101
        private const val MODEM_RTS_ENABLE = 0x0202
        /** 3,000,000 / 6 = 500,000 — the exact wValue seen in the vendor capture. */
        private const val BAUD_DIVISOR_500000 = 0x0006
        private const val LATENCY_TIMER_MS = 1

        private const val RESET_COUNT = 5
        private const val PURGE_TX_COUNT = 6
        private const val POST_PURGE_DELAY_MS = 125L
        private const val PRE_COMMAND_DELAY_MS = 900L

        private const val FTDI_HEADER_LENGTH = 2
        private const val CONTROL_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 2000
        private const val READ_TIMEOUT_MS = 2000
    }
}
