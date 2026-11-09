package com.offgrid.messenger

/** The ESP32's periodic LINK record measures ESP-NOW RF, not phone-to-ESP32 BLE RSSI.
 * No inferred/guessed RSSI values; missing peers and malformed records remain unknown.
 */
data class RadioLink(
    val peer: String,
    val rssiDbm: Int?,
    val phy: String,
    val phyMode: String,
    val rateMode: String,
    val changes: Long?,
    val normalPeerAgeMs: Long?
) {
    val hasPeer: Boolean get() = peer != "BROADCAST"
    val signalLabel: String get() = when {
        normalPeerAgeMs != null && (normalPeerAgeMs < 0 || normalPeerAgeMs > 12_000) -> "Peer not heard"
        else -> rssiDbm?.let { "$it dBm" } ?: "No peer signal"
    }
    val phyLabel: String get() = when (phy) {
        "0.25", "25" -> "LR 250 kbps"
        "0.5", "50" -> "LR 500 kbps"
        "1", "6", "12" -> "$phy Mbps"
        else -> if (phy.isBlank()) "Unknown PHY" else "$phy Mbps"
    }
    val summary: String get() = if (rssiDbm == null || signalLabel == "Peer not heard")
        "$signalLabel · $rateMode" else "$signalLabel · $phyLabel · $rateMode"

    companion object {
        val choices: List<String> = listOf("AUTO", "SPEED", "RANGE", "LR500", "LR250")
        fun parse(line: String): RadioLink? {
            if (!line.startsWith("LINK|")) return null
            val fields = line.split('|')
            if (fields.size < 3 || fields[1].isBlank()) return null
            val values = fields.drop(2).mapNotNull { field ->
                val i = field.indexOf('=')
                if (i <= 0 || i == field.lastIndex) null else field.substring(0, i) to field.substring(i + 1)
            }.toMap()
            val peer = fields[1]
            val dbm = values["RSSI"]?.toIntOrNull()?.takeIf { peer != "BROADCAST" && it in -125..-1 }
            val mode = values["MODE"]?.takeIf { it in choices } ?: "AUTO"
            return RadioLink(peer, dbm, values["PHY"] ?: "", values["PHYMODE"] ?: "",
                mode, values["CHANGES"]?.toLongOrNull(), values["REGAGE"]?.toLongOrNull())
        }
    }
}
