package com.offgrid.messenger
data class ChatItem(val id:String,val text:String,val outgoing:Boolean,val timestamp:Long,
    val status:String="sent",val kind:Int=1,val bytes:ByteArray?=null,val mime:String="",
    val width:Int=0,val height:Int=0,val durationMs:Int=0,val progress:Int=0,val speedKiBs:Float=0f,val edited:Boolean=false,val deleted:Boolean=false)
