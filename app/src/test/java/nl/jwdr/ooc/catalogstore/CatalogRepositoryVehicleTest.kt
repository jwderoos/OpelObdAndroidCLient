package nl.jwdr.ooc.catalogstore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.catalog.CanBus
import nl.jwdr.ooc.catalog.EcuAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogRepositoryVehicleTest {

    private val dao = FakeCatalogDao()
    private val repository = CatalogRepository(dao, clock = { 1234L })

    private fun canEcu(modelYear: String, vehicle: String, name: String, requestId: Int) = EcuEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        modelYear = modelYear,
        vehicle = vehicle,
        groupName = "Body",
        name = name,
        systemName = "$name system",
        protocol = "CAN",
        builtinFunction = null,
        catalogKey = null,
        addressType = "CAN",
        canBus = "HSCAN",
        bitRateTenthsKbps = 5000,
        requestId = requestId,
        secondaryId = 0,
        responseId = requestId + 8,
        baudRate = null,
        klineAddress = null,
        initType = null,
        extra = null,
    )

    private fun klineEcu(modelYear: String, vehicle: String, name: String) = canEcu(
        modelYear = modelYear,
        vehicle = vehicle,
        name = name,
        requestId = 0,
    ).copy(
        addressType = "KLINE",
        canBus = null,
        bitRateTenthsKbps = null,
        requestId = null,
        secondaryId = null,
        responseId = null,
        baudRate = 10400,
        klineAddress = 0x11,
        initType = 1,
        extra = 0,
    )

    private suspend fun storeCatalog(vararg ecus: EcuEntity) {
        dao.replaceCatalog(
            CatalogPayload(
                catalog = CatalogEntity(label = "test", sourceHash = "h", importedAtEpochMillis = 1L),
                ecus = ecus.toList(),
                files = emptyList(),
            ),
        )
    }

    @Test
    fun `vehicles lists each distinct model year and vehicle pair once, sorted`() = runTest {
        storeCatalog(
            canEcu("2007", "Corsa-D", "Engine", 0x7E0),
            canEcu("2007", "Corsa-D", "ABS", 0x241),
            canEcu("2005", "Astra-H", "Engine", 0x7E0),
        )

        assertEquals(
            listOf(VehicleRef("2005", "Astra-H"), VehicleRef("2007", "Corsa-D")),
            repository.vehicles.first(),
        )
    }

    @Test
    fun `selectVehicle persists the selection and null clears it`() = runTest {
        storeCatalog(canEcu("2005", "Astra-H", "Engine", 0x7E0))

        repository.selectVehicle(VehicleRef("2005", "Astra-H"))
        assertEquals(VehicleRef("2005", "Astra-H"), repository.selectedVehicle.first())

        repository.selectVehicle(null)
        assertNull(repository.selectedVehicle.first())
    }

    @Test
    fun `canEcusFor returns only that vehicle's CAN ECUs, as definitions`() = runTest {
        storeCatalog(
            canEcu("2005", "Astra-H", "Engine", 0x7E0),
            klineEcu("2005", "Astra-H", "Radio"),
            canEcu("2007", "Corsa-D", "ABS", 0x241),
        )

        val definitions = repository.canEcusFor(VehicleRef("2005", "Astra-H"))

        assertEquals(listOf("Engine"), definitions.map { it.name })
        assertEquals(
            EcuAddress.Can(CanBus.HSCAN, 5000, 0x7E0, 0, 0x7E8),
            definitions.single().address,
        )
    }
}
