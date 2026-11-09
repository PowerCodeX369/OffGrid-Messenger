package com.offgrid.messenger

fun main() {
    val now = System.currentTimeMillis()
    fun msg(id:String, outgoing:Boolean, state:String="delivered", deleted:Boolean=false) =
        ChatItem(id, id, outgoing, now, status=state, deleted=deleted)
    val samples=listOf(
        msg("in1",false),msg("in2",false),msg("inread",false,"read"),
        msg("out",true),msg("inplayed",false,"played"),msg("hidden",false,deleted=true)
    )
    check(ConversationUiRules.unreadCount(samples)==2)
    check(ConversationUiRules.unreadCount(samples.filterNot{it.id=="in1"})==1)
    check(ConversationUiRules.unreadCount(samples.map { if(it.id=="in2")it.copy(status="read") else it })==1)
    check(ConversationUiRules.unreadCount(emptyList())==0)
    check(ConversationUiRules.followNewMessage(true,20,true)) // our sent message always follows
    check(ConversationUiRules.followNewMessage(false,0,false)) // incoming when at bottom
    check(ConversationUiRules.followNewMessage(false,2,false)) // adjacent to last item
    check(!ConversationUiRules.followNewMessage(false,15,false)) // preserve reading history
    check(!ConversationUiRules.followNewMessage(false,0,true)) // finger actively scrolling
    println("PASS: unread badges and auto-follow policies")
}
