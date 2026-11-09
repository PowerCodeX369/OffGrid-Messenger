package com.offgrid.messenger

/** UI-only rules: no changes to BLE, crypto, ESP-NOW, or receipt wire format. */
object ConversationUiRules {
    /** Unread is an incoming, nondeleted message without a READ receipt from this device. */
    fun unreadCount(items:List<ChatItem>):Int = items.count {
        !it.outgoing && !it.deleted && it.status=="delivered"
    }

    /** Follow messages as in common messengers, without yanking a reader who scrolled up.
     * Outgoing messages always follow; incoming follow when the user is near the newest.
     */
    fun followNewMessage(outgoing:Boolean,firstVisibleIndex:Int,isScrollInProgress:Boolean):Boolean =
        outgoing || (firstVisibleIndex<=2 && !isScrollInProgress)
}
