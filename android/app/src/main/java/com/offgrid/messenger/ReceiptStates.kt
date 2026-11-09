package com.offgrid.messenger

/** Display never upgrades a receipt without an authenticated event.
 * Radio queued is NOT remote delivery; the second status requires OGM4 DELIVERED.
 * Voice uses PLAYED rather than READ to turn blue.
 */
enum class ReceiptVisual { WAITING, SENT, DELIVERED, READ, FAILED }
object ReceiptStates {
    fun visual(status: String, kind: Int): ReceiptVisual = when {
        status == "failed" -> ReceiptVisual.FAILED
        kind == OgmCodec.VOICE && status == "played" -> ReceiptVisual.READ
        kind == OgmCodec.VOICE && status == "read" -> ReceiptVisual.DELIVERED
        status == "read" || status == "played" -> ReceiptVisual.READ
        status == "delivered" -> ReceiptVisual.DELIVERED
        status == "sent" -> ReceiptVisual.SENT
        else -> ReceiptVisual.WAITING
    }
    private fun rank(status: String): Int = when(status) {
        "sending" -> 0; "sent" -> 1; "delivered" -> 2; "read" -> 3; "played" -> 4; else -> -1
    }
    fun advance(previous: String, incoming: String): String = when {
        incoming == "failed" -> if(rank(previous) >= 2) previous else "failed"
        previous == "failed" && rank(incoming) < 2 -> previous
        rank(incoming) > rank(previous) -> incoming
        else -> previous
    }
}
