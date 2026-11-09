package com.offgrid.messenger

/** Pure HCI decision logic, independent from Compose pointers and device density.
 * A swipe-up locks recording; a swipe-left cancels. Lock never auto-sends.
 */
object VoiceGestureRules {
    enum class Action { NONE, CANCEL, LOCK }
    fun classify(dxDp:Float,dyDp:Float,locked:Boolean,cancelled:Boolean):Action = when {
        locked || cancelled -> Action.NONE
        dyDp <= -66f -> Action.LOCK
        dxDp <= -78f -> Action.CANCEL
        else -> Action.NONE
    }
}
