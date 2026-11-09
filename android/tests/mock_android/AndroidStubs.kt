// Tiny executable fake BLE implementation for testing GATT state sequencing.
// It is NOT Android SDK. Only tests ordering, chunk math and state contracts.
package android.bluetooth
import android.content.Context
import java.util.UUID
object FakeBluetooth { var gatt = BluetoothGatt() }
class BluetoothAdapter { val isEnabled:Boolean=true; fun getRemoteDevice(a:String)=BluetoothDevice() }
class BluetoothManager { val adapter: BluetoothAdapter = BluetoothAdapter() }
class BluetoothDevice {
  companion object { const val TRANSPORT_LE:Int=2 }
  fun connectGatt(c:Context, autoConnect:Boolean, callback:BluetoothGattCallback,transport:Int):BluetoothGatt? {
    FakeBluetooth.gatt.callback=callback
    return FakeBluetooth.gatt
  }
}
class BluetoothGatt {
  companion object {
    const val GATT_SUCCESS:Int = 0
    const val CONNECTION_PRIORITY_HIGH:Int=1
    const val CONNECTION_PRIORITY_BALANCED:Int=0
  }
  var callback:BluetoothGattCallback?=null
  val notify = BluetoothGattCharacteristic(UUID.fromString("7b22d904-7bd5-48ba-b641-4e8026030b03"))
  val write = BluetoothGattCharacteristic(UUID.fromString("7b22d904-7bd5-48ba-b641-4e8026030b02"))
  val service=BluetoothGattService(write,notify)
  val services:List<BluetoothGattService> = listOf(service)
  var mtuRequestSucceeds:Boolean=true
  var mtuRequests=mutableListOf<Int>()
  var priorityRequests=mutableListOf<Int>()
  var descriptorWrites=0
  var writes=mutableListOf<ByteArray>()
  fun disconnect() {}
  fun close() {}
  fun discoverServices():Boolean=true
  fun getService(uuid:UUID):BluetoothGattService?=service
  fun requestConnectionPriority(priority:Int):Boolean{priorityRequests.add(priority);return true}
  fun requestMtu(mtu:Int):Boolean{mtuRequests.add(mtu);return mtuRequestSucceeds}
  fun writeCharacteristic(c:BluetoothGattCharacteristic,data:ByteArray,writeType:Int):Int{ writes.add(data.clone());return GATT_SUCCESS }
  fun writeCharacteristic(c:BluetoothGattCharacteristic):Boolean{writes.add(c.value?.clone()?:byteArrayOf());return true}
  fun setCharacteristicNotification(c:BluetoothGattCharacteristic,enabled:Boolean):Boolean=true
  fun writeDescriptor(c:BluetoothGattDescriptor,value:ByteArray):Int{descriptorWrites++;return GATT_SUCCESS}
  fun writeDescriptor(c:BluetoothGattDescriptor):Boolean{descriptorWrites++;return true}
}
open class BluetoothGattCallback {
 open fun onConnectionStateChange(client:BluetoothGatt,status:Int,newState:Int) {}
 open fun onMtuChanged(client:BluetoothGatt,mtu:Int,status:Int) {}
 open fun onServicesDiscovered(client:BluetoothGatt,status:Int) {}
 open fun onDescriptorWrite(client:BluetoothGatt,descriptor:BluetoothGattDescriptor,status:Int) {}
 open fun onCharacteristicChanged(client:BluetoothGatt,characteristic:BluetoothGattCharacteristic,value:ByteArray) {}
 open fun onCharacteristicChanged(client:BluetoothGatt,characteristic:BluetoothGattCharacteristic) {}
 open fun onCharacteristicWrite(client:BluetoothGatt,characteristic:BluetoothGattCharacteristic,status:Int) {}
}
class BluetoothGattService(private val write:BluetoothGattCharacteristic,private val notify:BluetoothGattCharacteristic){
 fun getCharacteristic(uuid:UUID):BluetoothGattCharacteristic?=listOf(write,notify).firstOrNull{it.uuid==uuid}
}
class BluetoothGattCharacteristic(val uuid:UUID){companion object {const val WRITE_TYPE_DEFAULT:Int=2};var value:ByteArray?=null;fun getDescriptor(uuid:UUID):BluetoothGattDescriptor?=BluetoothGattDescriptor()}
class BluetoothGattDescriptor{companion object{val ENABLE_NOTIFICATION_VALUE=byteArrayOf(1,0)};var value:ByteArray?=null}
object BluetoothProfile{const val STATE_CONNECTED:Int=2}
