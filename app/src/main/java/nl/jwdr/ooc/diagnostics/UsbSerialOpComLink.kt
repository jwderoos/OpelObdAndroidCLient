package nl.jwdr.ooc.diagnostics

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.jwdr.ooc.transport.opcom.OpComLink

/**
 * [OpComLink] over the OP-COM clone's USB serial interface.
 *
 * USB descriptor probing confirmed this is a genuine FTDI chip under
 * AUTO-M3's own custom VID:PID ([VENDOR_ID]:[PRODUCT_ID]) — not in
 * usb-serial-for-android's default FTDI device table, hence [PROBE_TABLE].
 * [BAUD_RATE] matches the interface's publicly documented 500 kBit/s, 8N1,
 * no-flow-control link and was confirmed by probing real hardware.
 *
 * The UI gates device selection behind an already-granted [UsbManager]
 * permission before a device can reach this class; all it does here is move
 * bytes, exactly like [BluetoothSppLink] for the ELM327 link.
 */
class UsbSerialOpComLink(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    /**
     * Traces every raw byte written/read plus the init-sequence timeline.
     * Off by default; wired to the app's "verbose adapter logging" debug
     * setting in `AppContainer.buildTransport` so a future session can
     * capture fine-grained USB traffic on real hardware without recompiling.
     */
    private val verboseLogging: Boolean = false,
) : OpComLink {

    private var port: UsbSerialPort? = null

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val driver = UsbSerialProber(PROBE_TABLE).probeDevice(device)
                ?: throw IOException("no FTDI driver matched OP-COM device ${device.deviceName}")
            val connection = usbManager.openDevice(device)
                ?: throw IOException("failed to open ${device.deviceName} — USB permission not granted?")
            val serialPort = driver.ports.first() as FtdiSerialDriver.FtdiSerialPort
            serialPort.open(connection)
            if (verboseLogging) Log.i(TAG, "open: requesting baud=$BAUD_RATE 8N1, latency=${LATENCY_TIMER_MS}ms")
            serialPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort.setLatencyTimer(LATENCY_TIMER_MS)
            // usb-serial-for-android leaves DTR/RTS disabled by default (FtdiSerialDriver's
            // reset control transfer bakes that in) and the interface stays silent until
            // they're asserted. Order, latency timer, and the repeated purge below all match
            // a real init sequence captured via USB packet trace from the vendor software —
            // a single purge reliably left a stale/corrupted byte for the real handshake to
            // trip over.
            serialPort.rts = true
            serialPort.dtr = true
            repeat(TX_PURGE_COUNT) { serialPort.purgeHwBuffers(true, false) }
            serialPort.purgeHwBuffers(false, true)
            // The vendor software waits over a full second (1.03s measured via USB packet
            // trace) between finishing this init sequence and sending its first real command —
            // the interface's firmware is still booting up until then, and every earlier attempt
            // without this delay only ever saw its pre-boot idle chatter, regardless of what was
            // configured beforehand.
            delay(BOOT_SETTLE_DELAY_MS)
            if (verboseLogging) Log.i(TAG, "open: settle delay elapsed, port ready for first command")
            port = serialPort
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { port?.close() }
            port = null
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val p = port ?: throw IOException("link is not open")
            if (verboseLogging) Log.i(TAG, "write [${data.joinToString(" ") { "%02x".format(it) }}]")
            p.write(data, WRITE_TIMEOUT_MS)
        }
    }

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        val p = port ?: throw IOException("link is not open")
        val buffer = ByteArray(READ_BUFFER_SIZE)
        // usb-serial-for-android's read() returns 0 on a plain timeout, not
        // on a closed port (that throws) — loop past empty timeouts to give
        // OpComLink.read() honest suspend-until-data semantics.
        var n = 0
        while (n == 0) {
            n = p.read(buffer, READ_TIMEOUT_MS)
        }
        if (verboseLogging) Log.i(TAG, "read [${buffer.copyOf(n).joinToString(" ") { "%02x".format(it) }}]")
        buffer.copyOf(n)
    }

    companion object {
        const val VENDOR_ID = 0x0403
        const val PRODUCT_ID = 0x4F50

        private const val BAUD_RATE = 500000
        private const val WRITE_TIMEOUT_MS = 2000
        private const val READ_TIMEOUT_MS = 2000
        private const val READ_BUFFER_SIZE = 256
        private const val LATENCY_TIMER_MS = 1
        private const val TX_PURGE_COUNT = 6
        private const val BOOT_SETTLE_DELAY_MS = 1100L
        private const val TAG = "UsbSerialOpComLink"

        private val PROBE_TABLE = ProbeTable().apply {
            addProduct(VENDOR_ID, PRODUCT_ID, FtdiSerialDriver::class.java)
        }
    }
}
