package nl.jwdr.ooc.catalogstore

import nl.jwdr.ooc.catalog.CanBus
import nl.jwdr.ooc.catalog.EcuAddress
import nl.jwdr.ooc.catalog.EcuDefinition
import nl.jwdr.ooc.catalog.ImportedCatalog

/** Everything one import writes to the database. */
data class CatalogPayload(
    val catalog: CatalogEntity,
    val ecus: List<EcuEntity>,
    val files: List<CatalogFileEntity>,
)

fun ImportedCatalog.toPayload(label: String, importedAtEpochMillis: Long): CatalogPayload {
    val catalogId = CatalogEntity.SINGLETON_ID
    return CatalogPayload(
        catalog = CatalogEntity(
            id = catalogId,
            label = label,
            sourceHash = sourceHash,
            importedAtEpochMillis = importedAtEpochMillis,
        ),
        ecus = ecuDefinitions.map { it.toEntity(catalogId) },
        files = files.map {
            CatalogFileEntity(
                catalogId = catalogId,
                kind = it.kind.name,
                fileKey = it.key,
                fileName = it.fileName,
                content = it.bytes,
            )
        },
    )
}

private const val ADDRESS_CAN = "CAN"
private const val ADDRESS_KLINE = "KLINE"
private const val ADDRESS_NONE = "NONE"

fun EcuDefinition.toEntity(catalogId: Long): EcuEntity {
    val can = address as? EcuAddress.Can
    val kline = address as? EcuAddress.KLine
    return EcuEntity(
        catalogId = catalogId,
        modelYear = modelYear,
        vehicle = vehicle,
        groupName = group,
        name = name,
        systemName = systemName,
        protocol = protocol,
        builtinFunction = builtinFunction,
        catalogKey = catalogKey,
        addressType = when (address) {
            is EcuAddress.Can -> ADDRESS_CAN
            is EcuAddress.KLine -> ADDRESS_KLINE
            EcuAddress.None -> ADDRESS_NONE
        },
        canBus = can?.bus?.name,
        bitRateTenthsKbps = can?.bitRateTenthsKbps,
        requestId = can?.requestId,
        secondaryId = can?.secondaryId,
        responseId = can?.responseId,
        baudRate = kline?.baudRate,
        klineAddress = kline?.address,
        initType = kline?.initType,
        extra = kline?.extra,
    )
}

fun EcuEntity.toDefinition(): EcuDefinition = EcuDefinition(
    modelYear = modelYear,
    vehicle = vehicle,
    group = groupName,
    name = name,
    systemName = systemName,
    protocol = protocol,
    address = when (addressType) {
        ADDRESS_CAN -> EcuAddress.Can(
            bus = CanBus.valueOf(checkNotNull(canBus)),
            bitRateTenthsKbps = checkNotNull(bitRateTenthsKbps),
            requestId = checkNotNull(requestId),
            secondaryId = checkNotNull(secondaryId),
            responseId = checkNotNull(responseId),
        )
        ADDRESS_KLINE -> EcuAddress.KLine(
            baudRate = checkNotNull(baudRate),
            address = checkNotNull(klineAddress),
            initType = checkNotNull(initType),
            extra = checkNotNull(extra),
        )
        else -> EcuAddress.None
    },
    builtinFunction = builtinFunction,
    catalogKey = catalogKey,
)
