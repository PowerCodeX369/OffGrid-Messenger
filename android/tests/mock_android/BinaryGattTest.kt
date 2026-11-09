package com.offgrid.messenger
import android.bluetooth.*
import android.content.Context

private fun makeClient(mtu:Int):Triple<BleNodeClient,BluetoothGatt,MutableList<String>> {
    val g=BluetoothGatt();FakeBluetooth.gatt=g
    val events=mutableListOf<String>()
    val node=BleNodeClient(Context(),{ByteArray(32){1}},{_,_ -> },{},events::add)
    node.connect("AA:BB:CC:DD:EE:FF")
    g.callback!!.onConnectionStateChange(g,0,BluetoothProfile.STATE_CONNECTED)
    g.callback!!.onServicesDiscovered(g,0)
    if(mtu>=23)g.callback!!.onMtuChanged(g,mtu,0)
    g.callback!!.onDescriptorWrite(g,BluetoothGattDescriptor(),0)
    g.callback!!.onCharacteristicChanged(g,g.notify,("CHAL|"+"AA".repeat(16)+"\n").toByteArray())
    flushClientWrites(g)
    g.callback!!.onCharacteristicChanged(g,g.notify,"AUTH_OK\n".toByteArray())
    flushClientWrites(g)
    check(node.ready)
    return Triple(node,g,events)
}
fun main(){
    val original=ByteArray(180){((it*13+31) and 255).toByte()}
    // New firmware + negotiated 247: one 185-byte write, short queued correlation.
    val (fast,g,logs)=makeClient(247)
    g.callback!!.onCharacteristicChanged(g,g.notify,"BINCAP|1|180\n".toByteArray())
    check(logs.any{it.contains("active=true")})
    val before=g.writes.size
    val token=fast.sendFrame(original) ?: error("Missing binary token")
    flushClientWrites(g,before)
    val sent=g.writes.drop(before)
    check(sent.size==1 && sent.single().size==185)
    check(sent.single().copyOfRange(5,185).contentEquals(original))
    check(sent.single()[3].toInt() and 255==token and 255)
    val ack=BleBinaryCodec.queued("BQUEUED|$token|700|456") ?: error("No compact ACK")
    check(ack.token==token && ack.packetId=="456")
    fast.stop()
    // Old firmware doesn't advertise the capability: keep MSGHEX and QUEUED.
    val (legacy,l,events)=makeClient(247)
    val legacyBefore=l.writes.size
    check(legacy.sendFrame(original)==null)
    flushClientWrites(l,legacyBefore)
    val oldPayload=l.writes.drop(legacyBefore)
    check(oldPayload.size==3)
    check(oldPayload.joinToString(""){String(it)}=="MSGHEX ${OgmCodec.hex(original)}\n")
    legacy.stop()
    // Even new firmware must stay on ASCII if negotiated ATT MTU too small.
    val (small,s,smallEvents)=makeClient(23)
    s.callback!!.onCharacteristicChanged(s,s.notify,"BINCAP|1|180\n".toByteArray())
    check(smallEvents.any{it.contains("active=false")})
    val smallBefore=s.writes.size
    check(small.sendFrame(original)==null)
    flushClientWrites(s,smallBefore)
    check(s.writes.drop(smallBefore).all{it.size<=20})
    small.stop()
    println("PASS GATT: 1 binary write vs 3 ASCII writes, old-firmware/low-MTU fallbacks, BQUEUED token")
}
