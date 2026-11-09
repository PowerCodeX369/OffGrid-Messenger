package com.offgrid.messenger

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Message identifiers locally hidden by Delete for me. Stored ONLY inside
 * EncryptedHistory's separate Android Keystore AES-GCM file. */
object HiddenMessageIds {
    const val MAX_IDS=1024
    private val idPattern=Regex("[0-9a-fA-F]{16}")
    fun encode(ids:Set<String>):ByteArray {
        val ordered=ids.filter{idPattern.matches(it)}.takeLast(MAX_IDS).sorted()
        val out=ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(1)
            data.writeInt(ordered.size)
            ordered.forEach(data::writeUTF)
        }
        return out.toByteArray()
    }
    fun decode(raw:ByteArray):Set<String> {
        require(raw.size in 8..24000) {"Invalid hidden-message ledger"}
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            require(input.readInt()==1) {"Unsupported hidden-message ledger version"}
            val n=input.readInt()
            require(n in 0..MAX_IDS) {"Too many hidden message identifiers"}
            val found=LinkedHashSet<String>()
            repeat(n) {
                val id=input.readUTF()
                require(idPattern.matches(id)) {"Invalid message identifier"}
                found.add(id)
            }
            require(input.available()==0) {"Trailing hidden-message bytes"}
            found
        }
    }
}
