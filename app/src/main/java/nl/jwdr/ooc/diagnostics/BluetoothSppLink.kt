package nl.jwdr.ooc.diagnostics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.jwdr.ooc.transport.elm327.Elm327Link

/**
 * [Elm327Link] over a classic Bluetooth SPP socket. Deliberately dumb: all
 * ELM semantics live in the JVM-tested transport; this class only moves
 * ASCII over the socket.
 *
 * The UI gates adapter selection behind the BLUETOOTH_CONNECT runtime
 * permission before a device can reach this class, hence the suppression.
 */
@SuppressLint("MissingPermission")
class BluetoothSppLink(
    private val device: BluetoothDevice,
) : Elm327Link {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            input = s.inputStream
            output = s.outputStream
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
        }
    }

    override suspend fun write(data: String) {
        withContext(Dispatchers.IO) {
            val out = output ?: throw IOException("link is not open")
            out.write(data.toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    override suspend fun read(): String = withContext(Dispatchers.IO) {
        val stream = input ?: throw IOException("link is not open")
        // Per-call buffer: a cancelled read keeps blocking on the socket until
        // close() and must not share a buffer with the next read.
        val buffer = ByteArray(1024)
        val n = stream.read(buffer)
        if (n < 0) throw IOException("adapter closed the connection")
        String(buffer, 0, n, Charsets.US_ASCII)
    }

    private companion object {
        /** The well-known Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
