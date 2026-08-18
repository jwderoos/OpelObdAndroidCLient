package nl.jwdr.ooc.ui.navigation

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {

    @Test
    fun `every route is serializable for type-safe navigation`() {
        // Navigation Compose requires @Serializable routes; forgetting the
        // annotation only crashes at runtime, so verify it here.
        for (route in Route.all) {
            val encoded = Json.encodeToString(serializer(route::class.java), route)
            assertEquals("{}", encoded)
        }
    }

    @Test
    fun `there are seven screens as per the design spec`() {
        assertEquals(7, Route.all.size)
    }

    @Test
    fun `home menu drills into the five feature screens in workflow order`() {
        assertEquals(
            listOf(Route.EcuList, Route.FaultCodes, Route.LiveData, Route.OutputTests, Route.Coding),
            HOME_MENU.map { it.route },
        )
    }
}
