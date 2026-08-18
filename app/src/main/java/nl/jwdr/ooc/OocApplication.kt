package nl.jwdr.ooc

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.jwdr.ooc.catalogstore.CatalogDatabase
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport

/**
 * Composition root: the app-wide singletons, wired by hand. ViewModels reach
 * this through [containerViewModel][nl.jwdr.ooc.ui.containerViewModel].
 */
class AppContainer(context: Context) {

    /** Outlives any screen; hosts transport background work. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val catalogRepository: CatalogRepository by lazy {
        CatalogRepository(CatalogDatabase.get(context).catalogDao())
    }

    /**
     * Wired to [FakeEcuTransport] until adapter selection lands (#19, #20),
     * so the shell is drivable end-to-end and always badged as simulated.
     */
    val diagnosticsManager: DiagnosticsManager by lazy {
        DiagnosticsManager(FakeEcuTransport(applicationScope).apply { scriptDemoScanResponses() })
    }
}

/**
 * Demo script so the bus scan is drivable against the fake transport: ECUs
 * on an even request ID answer the readDTCByStatus probe with one stored
 * fault; odd IDs stay silent (absent). The response is offered on both
 * common GMLAN response-ID layouts (request+8 and request+0x400); the
 * session only accepts the one matching the catalog's address map.
 */
private fun FakeEcuTransport.scriptDemoScanResponses() {
    val pad = 0xAA.toByte()
    onMatch { it.data.size >= 2 && it.data[0].toInt() == 0x04 && it.data[1].toInt() == 0x18 }
        .respondBy { request ->
            if (request.id % 2 != 0) return@respondBy emptyList()
            val payload = byteArrayOf(0x05, 0x58, 0x01, 0x01, 0x70, 0xE1.toByte(), pad, pad)
            listOf(CanFrame(request.id + 8, payload), CanFrame(request.id + 0x400, payload))
        }
}

class OocApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
