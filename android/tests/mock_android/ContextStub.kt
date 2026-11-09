package android.content
open class Context { fun <T> getSystemService(clazz:Class<T>): T {
  @Suppress("UNCHECKED_CAST")
  return android.bluetooth.BluetoothManager() as T
} }
