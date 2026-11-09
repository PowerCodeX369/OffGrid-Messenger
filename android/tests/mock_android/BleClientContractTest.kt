package com.offgrid.messenger
import android.bluetooth.*
import android.content.Context

fun flushClientWrites(g:BluetoothGatt, from:Int=0):Int {
 var callbacks=0
 var seen=from
 while(seen<g.writes.size && callbacks<200) {
   seen++
   g.callback!!.onCharacteristicWrite(g,g.write,BluetoothGatt.GATT_SUCCESS)
   callbacks++
 }
 check(callbacks<200) { "Possible write-loop" }
 return seen
}

fun runGATTScenario(requestOk:Boolean,expectedChunkSize:Int) {
 val g=BluetoothGatt();g.mtuRequestSucceeds=requestOk;FakeBluetooth.gatt=g
 val diagnostics=mutableListOf<String>();val statuses=mutableListOf<String>()
 val node=BleNodeClient(Context(),{ByteArray(32){1}},{s,_ -> statuses.add(s)},{},diagnostics::add)
 node.connect("AA:BB:CC:DD:EE:FF")
 g.callback!!.onConnectionStateChange(g,BluetoothGatt.GATT_SUCCESS,BluetoothProfile.STATE_CONNECTED)
 check(g.priorityRequests.contains(BluetoothGatt.CONNECTION_PRIORITY_HIGH))
 g.callback!!.onServicesDiscovered(g,BluetoothGatt.GATT_SUCCESS)
 check(g.mtuRequests==listOf(247))
 check(g.descriptorWrites == if(requestOk) 0 else 1) {"CCCD started during MTU operation"}
 if(requestOk) {
   g.callback!!.onMtuChanged(g,247,BluetoothGatt.GATT_SUCCESS)
   check(g.descriptorWrites==1)
 }
 g.callback!!.onDescriptorWrite(g,BluetoothGattDescriptor(),BluetoothGatt.GATT_SUCCESS)
 val challenge="CHAL|" + "00".repeat(16)+"\n"
 g.callback!!.onCharacteristicChanged(g,g.notify,challenge.toByteArray())
 flushClientWrites(g)
 g.callback!!.onCharacteristicChanged(g,g.notify,"AUTH_OK\n".toByteArray())
 flushClientWrites(g)
 check(node.ready)
 val before=g.writes.size
 val command="MSGHEX "+"A".repeat(360)
 node.send(command)
 flushClientWrites(g,before)
 val data=g.writes.drop(before)
 check(data.isNotEmpty())
 check(data.map{it.size}.maxOrNull()!!<=expectedChunkSize)
 check(data.map{it.size}.sum()==command.length+1)
 check(data.joinToString(""){String(it)} == "$command\n")
 val expectedChunks=(command.length+expectedChunkSize)/expectedChunkSize
 check(data.size==expectedChunks) {"$requestOk: expected $expectedChunks chunks got ${data.size}"}
 check(diagnostics.any{it.contains("MTU")})
 println("PASS GATT MTU request=$requestOk: encrypted command ${data.sumOf{it.size}} bytes in ${data.size} serialized writes of <=$expectedChunkSize bytes")
 node.stop()
}
fun runAlreadyNegotiatedMtuScenario() {
 val g=BluetoothGatt();FakeBluetooth.gatt=g
 val node=BleNodeClient(Context(),{ByteArray(32){1}},{_,_ -> },{}, {})
 node.connect("AA:BB:CC:DD:EE:FF")
 g.callback!!.onConnectionStateChange(g,BluetoothGatt.GATT_SUCCESS,BluetoothProfile.STATE_CONNECTED)
 // Android 14 or another GATT client can negotiate the ATT MTU before service discovery.
 g.callback!!.onMtuChanged(g,247,BluetoothGatt.GATT_SUCCESS)
 check(g.descriptorWrites==0) {"Unsolicited MTU callback started CCCD before service discovery"}
 g.callback!!.onServicesDiscovered(g,BluetoothGatt.GATT_SUCCESS)
 check(g.mtuRequests.isEmpty()) {"Duplicate MTU request after existing negotiation"}
 check(g.descriptorWrites==1) {"Missing CCCD after existing negotiation"}
 node.stop()
 println("PASS: pre-negotiated MTU callback ordering does not race service discovery")
}
fun main() {
 runGATTScenario(true,180)
 runGATTScenario(false,20)
 runAlreadyNegotiatedMtuScenario()
}
