package com.offgrid.messenger

/** Artificial radio ACK delay benchmark, not a real BLE/ESP-NOW throughput result. */
private class Clock {
  var tick=1000L
  val events=linkedMapOf<Runnable,Long>()
  fun post(r:Runnable,ms:Long){events[r]=tick+ms}
  fun cancel(r:Runnable){events.remove(r)}
  fun until(finished:()->Boolean) {
    var guard=0
    while(!finished()&&guard++<20000) {
      val (r,whenMs)=events.minByOrNull {it.value}?.let{it.key to it.value} ?: error("Deadlocked")
      events.remove(r);tick=whenMs;r.run()
    }
    check(finished())
  }
}
fun main() {
  fun run(width:Int):Long {
    val n=36;val clk=Clock();val start=clk.tick
    val bytes=List(n){byteArrayOf(it.toByte())}
    var success=false
    lateinit var sender:MediaTransferWindow
    sender=MediaTransferWindow(bytes,width,{clk.tick},clk::post,clk::cancel,{
      val idx=it[0].toInt() and 255
      clk.post(Runnable { sender.onQueued(OgmCodec.hex(bytes[idx]),"1",(100+idx).toString()) },4)
      clk.post(Runnable { sender.onDelivered("1",(100+idx).toString()) },120)
    },{_,_->},{success=true},{error(it)},{error("Unexpected pause")})
    sender.startOrResume();clk.until{success};return clk.tick-start
  }
  val old=run(1);val optimized=run(3)
  check(old>optimized*2.5)
  println("SIMULATED 36-fragment RTT 120ms: old=$old ms, window3=$optimized ms")
  println("Note: scheduling model ONLY; actual phone + ESP-NOW throughput must be measured on hardware")
}
