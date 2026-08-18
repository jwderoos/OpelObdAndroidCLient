package nl.jwdr.ooc.diagnostics

import nl.jwdr.ooc.catalog.BlockReading

/** Long-format CSV serialization of live measuring-block readings. */
object LiveDataCsv {

    const val HEADER = "timestamp_ms,ecu,block,label,value,unit"

    /** One CSV line per OBD-II PID reading, in [values] order. */
    fun obd2Lines(timestampMs: Long, ecuName: String, values: List<Obd2Value>): List<String> =
        values.map { value ->
            listOf(
                timestampMs.toString(),
                ecuName,
                "OBD-II",
                value.pid.name,
                value.display,
                value.pid.unit ?: "",
            ).joinToString(",") { escape(it) }
        }

    /** One CSV line per row of [reading], in table order. */
    fun lines(timestampMs: Long, ecuName: String, reading: BlockReading): List<String> =
        reading.rows.map { row ->
            listOf(
                timestampMs.toString(),
                ecuName,
                reading.block.title,
                row.row.label,
                if (row.raw == null) "" else row.display,
                row.row.unit ?: "",
            ).joinToString(",") { escape(it) }
        }

    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
}
