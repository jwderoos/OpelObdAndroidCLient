package nl.jwdr.ooc

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.jwdr.ooc.catalogstore.CatalogDatabase
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
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
        DiagnosticsManager(FakeEcuTransport(applicationScope))
    }
}

class OocApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
