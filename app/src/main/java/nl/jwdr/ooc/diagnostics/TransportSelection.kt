package nl.jwdr.ooc.diagnostics

/**
 * The user's adapter choice, persisted across launches as one string
 * (`demo`, `elm327|<mac>|<name>`, or `opcom-usb`). Anything unreadable
 * decodes to [Demo] so a bad preference can never brick startup.
 */
sealed interface TransportSelection {

    data object Demo : TransportSelection {
        override fun encode(): String = "demo"
    }

    data class Elm327Bluetooth(val address: String, val name: String) : TransportSelection {
        override fun encode(): String = "elm327|$address|$name"
    }

    /**
     * The OP-COM clone USB dongle. Unlike Bluetooth's paired-device list,
     * there is only ever one such adapter attached at a time, so this
     * carries no device identifier — the one matching (vendor, product) id
     * is looked up from [android.hardware.usb.UsbManager] at connect time.
     */
    data object OpComUsb : TransportSelection {
        override fun encode(): String = "opcom-usb"
    }

    fun encode(): String

    companion object {
        fun decode(persisted: String?): TransportSelection {
            if (persisted == null) return Demo
            // limit = 3: Bluetooth device names may themselves contain '|'.
            val parts = persisted.split('|', limit = 3)
            return when {
                persisted == "demo" -> Demo
                persisted == "opcom-usb" -> OpComUsb
                parts.size == 3 && parts[0] == "elm327" && parts[1].isNotEmpty() ->
                    Elm327Bluetooth(address = parts[1], name = parts[2])
                else -> Demo
            }
        }
    }
}
