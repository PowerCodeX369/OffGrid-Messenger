package com.offgrid.messenger
fun main() {
    val frames=List(9) {byteArrayOf(it.toByte(),(it+42).toByte())}
    val tx=mutableListOf<Int>()
    var nextToken=70
    var elapsed=1000L
    val timers=linkedMapOf<Runnable,Long>()
    val sender=MediaTransferWindow(frames,3,{elapsed},{r,d->timers[r]=elapsed+d},{timers.remove(it)},
        { frame ->tx.add(frame[0].toInt() and 255); nextToken++ },
        {_,_->},{},{error("Unexpected failure: $it")},{})
    sender.startOrResume()
    check(tx==listOf(0,1,2))
    check(!sender.onBinaryQueued(1,"900","1"))
    check(sender.onBinaryQueued(72,"900","102"))
    check(sender.onBinaryQueued(70,"900","100"))
    check(sender.onBinaryQueued(71,"900","101"))
    check(!sender.onDelivered("901","100"))
    check(sender.onDelivered("900","101"))
    check(tx==listOf(0,1,2,3))
    check(sender.onBinaryQueued(73,"900","103"))
    check(sender.onDelivered("900","102"))
    check(sender.onDelivered("900","100"))
    check(sender.onDelivered("900","103"))
    var t=74
    var id=104
    while(sender.completedCount<9){
        check(sender.onBinaryQueued(t++,"900",(id).toString()))
        check(sender.onDelivered("900",(id++).toString()))
    }
    check(sender.completedCount==9)
    println("PASS binary BLE MediaWindow: out-of-order token/ID, 3 in-flight, no false delivery")
}
