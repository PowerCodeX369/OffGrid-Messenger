package com.offgrid.messenger

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

fun main() {
    val id="AABBCCDDEEFF1200"
    val incoming=ChatItem(id,"Hello",false,1700000000000L,"delivered")
    val outgoing=incoming.copy(outgoing=true)
    val edit=MessageMutation.editPayload(id,"Corrected 🌍")
    check(edit.size>8 && MessageMutation.target(edit)==id)
    val edited=MessageMutation.apply(incoming,OgmCodec.EDIT,edit)!!
    check(edited.text=="Corrected 🌍" && edited.edited && !edited.deleted)
    check(MessageMutation.apply(outgoing,OgmCodec.EDIT,edit)==null)
    check(MessageMutation.apply(incoming,OgmCodec.EDIT,MessageMutation.editPayload("0000000000000000","Attack"))==null)
    check(MessageMutation.apply(incoming,OgmCodec.EDIT,ByteArray(3))==null)
    check(runCatching{MessageMutation.editPayload(id,"x".repeat(501))}.isFailure)
    // Replay protection: an older captured EDIT must not overwrite a newer edit.
    val e1=MessageMutation.apply(incoming,OgmCodec.EDIT,MessageMutation.editPayload(id,"v1"),1000L)!!
    val e2=MessageMutation.apply(e1,OgmCodec.EDIT,MessageMutation.editPayload(id,"v2"),2000L)!!
    check(e2.text=="v2" && e2.editedAt==2000L)
    check(MessageMutation.apply(e2,OgmCodec.EDIT,MessageMutation.editPayload(id,"v1"),1000L)==null)
    check(MessageMutation.apply(e2,OgmCodec.EDIT,MessageMutation.editPayload(id,"v3"),2000L)!!.text=="v3")
    check(HistoryCodec.decode(HistoryCodec.encode(listOf(e2)))[0].editedAt==2000L)
    val deleted=MessageMutation.apply(edited,OgmCodec.DELETE,MessageMutation.deletePayload(id))!!
    check(deleted.deleted && deleted.bytes==null && !deleted.edited)
    check(MessageMutation.apply(deleted,OgmCodec.EDIT,edit)==null)
    check(MessageMutation.apply(outgoing,OgmCodec.DELETE,MessageMutation.deletePayload(id))==null)
    val encoded=HistoryCodec.encode(listOf(edited,deleted))
    val decoded=HistoryCodec.decode(encoded)
    check(decoded[0].edited && decoded[0].text=="Corrected 🌍")
    check(decoded[1].deleted && decoded[1].bytes==null)
    // Phase 2.4 encrypted history format v1 stays readable after the v2 upgrade.
    val legacy=ByteArrayOutputStream().also { b->DataOutputStream(b).use {out->
        out.writeInt(1);out.writeInt(1);out.writeUTF(id);out.writeUTF("Legacy")
        out.writeBoolean(false);out.writeLong(1700000000000L);out.writeUTF("delivered")
        out.writeInt(1);out.writeUTF("");out.writeInt(0);out.writeInt(0);out.writeInt(0);out.writeInt(0)
    }}.toByteArray()
    val historic=HistoryCodec.decode(legacy).single()
    check(historic.text=="Legacy" && !historic.edited && !historic.deleted)
    val key=ByteArray(32){it.toByte()}
    for ((type,payload) in listOf(OgmCodec.EDIT to edit,OgmCodec.DELETE to MessageMutation.deletePayload(id))) {
        val frames=OgmCodec.seal(key,type,OgmCodec.newId(),payload)
        check(frames.isNotEmpty() && frames.all{OgmCodec.parseFrame(it)!=null})
        val first=OgmCodec.parseFrame(frames[0])!!
        val encrypted=frames.flatMap { OgmCodec.parseFrame(it)!!.bytes.toList() }.toByteArray()
        val decodedEnv=OgmCodec.decrypt(key,first.id,encrypted)
        check(decodedEnv.kind==type && decodedEnv.text.contentEquals(payload))
    }
    println("PASS Phase 2.4.1 edit/delete authentication scope, history migration, OGM4 crypto wire format")
}
