package com.offgrid.messenger

/** Authenticated OGM4 control payloads. Mutation is authorized only for a message
 * received FROM the authenticated peer (never for our outgoing messages).
 * Target IDs are exactly eight bytes; edit text is limited to 500 code points.
 */
object MessageMutation {
    const val MAX_EDIT_CHARS=500
    fun editPayload(id:String,text:String):ByteArray {
        require(text.isNotBlank() && text.codePointCount(0,text.length)<=MAX_EDIT_CHARS)
        return OgmCodec.unhex(id).also {require(it.size==8)}+text.toByteArray(Charsets.UTF_8)
    }
    fun deletePayload(id:String)=OgmCodec.unhex(id).also {require(it.size==8)}
    fun target(body:ByteArray):String? = if(body.size>=8)OgmCodec.hex(body.copyOfRange(0,8)) else null
    /** [sentAtMs] is the authenticated envelope timestamp. An EDIT older than the last applied
     * edit is a replay of captured traffic and is ignored, so a stale version can never
     * overwrite a newer one. Pass 0 (default) to skip the check. */
    fun apply(item:ChatItem, kind:Int, body:ByteArray, sentAtMs:Long=0L):ChatItem? {
        if(item.outgoing || item.deleted || item.id!=target(body))return null
        if(kind==OgmCodec.EDIT && sentAtMs>0 && sentAtMs<item.editedAt)return null
        return when(kind) {
            OgmCodec.EDIT -> if(item.kind==OgmCodec.TEXT && body.size in 9..2108) {
                val text=body.copyOfRange(8,body.size).toString(Charsets.UTF_8)
                if(text.isNotBlank() && text.codePointCount(0,text.length)<=MAX_EDIT_CHARS)
                    item.copy(text=text,edited=true,editedAt=maxOf(item.editedAt,sentAtMs)) else null
            } else null
            OgmCodec.DELETE -> if(body.size==8)
                item.copy(text="This message was deleted",bytes=null,deleted=true,edited=false) else null
            else -> null
        }
    }
}
