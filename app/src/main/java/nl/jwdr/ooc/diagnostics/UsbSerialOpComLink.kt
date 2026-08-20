package nl.jwdr.ooc.diagnostics

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.jwdr.ooc.transport.opcom.OpComLink

/**
 * [OpComLink] over the OP-COM clone's USB serial interface.
 *
 * USB descriptor probing confirmed this is a genuine FTDI chip under
 * AUTO-M3's own custom VID:PID ([VENDOR_ID]:[PRODUCT_ID]) — not in
 * usb-serial-for-android's default FTDI device table, hence [PROBE_TABLE].
 * The baud rate isn't documented anywhere; [BAUD_RATE] is an FTDI-common
 * default, unconfirmed against real hardware.
 *
 * The UI gates device selection behind an already-granted [UsbManager]
 * permission before a device can reach this class; all it does here is move
 * bytes, exactly like [BluetoothSppLink] for the ELM327 link.
 */
class UsbSerialOpComLink(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
) : OpComLink {

    private var port: UsbSerialPort? = null

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val driver = UsbSerialProber(PROBE_TABLE).probeDevice(device)
                ?: throw IOException("no FTDI driver matched OP-COM device ${device.deviceName}")
            val connection = usbManager.openDevice(device)
                ?: throw IOException("failed to open ${device.deviceName} — USB permission not granted?")
            val serialPort = driver.ports.first()
            serialPort.open(connection)
            serialPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
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
        buffer.copyOf(n)
    }

    companion object {
        const val VENDOR_ID = 0x0403
        const val PRODUCT_ID = 0x4F50

        private const val BAUD_RATE = 38400
        private const val WRITE_TIMEOUT_MS = 2000
        private const val READ_TIMEOUT_MS = 2000
        private const val READ_BUFFER_SIZE = 256

        private val PROBE_TABLE = ProbeTable().apply {
            addProduct(VENDOR_ID, PRODUCT_ID, FtdiSerialDriver::class.java)
        }
    }
}
