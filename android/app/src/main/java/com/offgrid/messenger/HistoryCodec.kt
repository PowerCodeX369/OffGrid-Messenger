package com.offgrid.messenger

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Pure, versioned plaintext serialization used ONLY inside an AES-GCM envelope.
 * Never write the output to a file or log directly. The EncryptedHistory store seals it.
 */
object HistoryCodec {
    const val MAX_MESSAGES=400
    const val MAX_MEDIA_BYTES=8_000_000
    fun encode(snapshot:List<ChatItem>):ByteArray {
        var remaining=MAX_MEDIA_BYTES
        val safe=snapshot.takeLast(MAX_MESSAGES).asReversed().map { item ->
            val n=item.bytes?.size ?: 0
            if(n<=remaining) {remaining-=n;item} else item.copy(bytes=null)
        }.asReversed()
        val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(3);out.writeInt(safe.size)
            safe.forEach { item ->
                out.writeUTF(item.id.take(120));out.writeUTF(item.text.take(4000))
                out.writeBoolean(item.outgoing);out.writeLong(item.timestamp)
                out.writeUTF(item.status);out.writeInt(item.kind);out.writeUTF(item.mime)
                out.writeInt(item.width);out.writeInt(item.height);out.writeInt(item.durationMs)
                val payload=item.bytes?.takeIf {it.size<=131_072}
                out.writeInt(payload?.size ?: 0);payload?.let(out::write)
                out.writeBoolean(item.edited);out.writeBoolean(item.deleted);out.writeLong(item.editedAt)
            }
        }
        return buffer.toByteArray()
    }
    fun decode(bytes:ByteArray):List<ChatItem> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val version=input.readInt();require(version in 1..3) {"Unsupported history version"}
        val count=input.readInt();require(count in 0..MAX_MESSAGES) {"History entry count exceeds limit"}
        List(count) {
            val id=input.readUTF();val text=input.readUTF()
            val outgoing=input.readBoolean();val time=input.readLong()
            val status=input.readUTF();val kind=input.readInt();val mime=input.readUTF()
            val width=input.readInt();val height=input.readInt();val duration=input.readInt()
            val len=input.readInt();require(len in 0..131_072) {"Invalid stored payload length"}
            val payload=if(len==0)null else ByteArray(len).also(input::readFully)
            ChatItem(id,text,outgoing,time,status,kind,payload,mime,width,height,duration,
                edited=if(version>=2)input.readBoolean() else false,
                deleted=if(version>=2)input.readBoolean() else false,
                editedAt=if(version>=3)input.readLong() else 0L)
        }
    }
}
