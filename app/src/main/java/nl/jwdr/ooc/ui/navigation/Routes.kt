package nl.jwdr.ooc.ui.navigation

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import nl.jwdr.ooc.R

/**
 * Type-safe Navigation Compose routes for the seven screens of the design
 * spec. All are argument-less at shell level; feature issues add argument
 * routes (e.g. per-ECU fault codes) as needed.
 */
sealed interface Route {

    @Serializable
    data object Home : Route

    /** @param autoScan start a bus scan on arrival (home's "scan all ECUs"). */
    @Serializable
    data class EcuList(val autoScan: Boolean = false) : Route

    /** @param ecuName ECU to read on arrival (ECU-list drill-in); null shows the picker. */
    @Serializable
    data class FaultCodes(val ecuName: String? = null) : Route

    @Serializable
    data object LiveData : Route

    @Serializable
    data object OutputTests : Route

    @Serializable
    data object Coding : Route

    @Serializable
    data object Settings : Route

    companion object {
        val all: List<Route> =
            listOf(Home, EcuList(), FaultCodes(), LiveData, OutputTests, Coding, Settings)
    }
}

/** One drill-in entry on the home hub screen. */
data class HomeMenuItem(
    @param:StringRes val titleRes: Int,
    val route: Route,
)

/** The home hub's feature entries, in diagnostic workflow order. */
val HOME_MENU: List<HomeMenuItem> = listOf(
    HomeMenuItem(R.string.screen_ecu_list, Route.EcuList()),
    HomeMenuItem(R.string.screen_fault_codes, Route.FaultCodes()),
    HomeMenuItem(R.string.screen_live_data, Route.LiveData),
    HomeMenuItem(R.string.screen_output_tests, Route.OutputTests),
    HomeMenuItem(R.string.screen_coding, Route.Coding),
)
