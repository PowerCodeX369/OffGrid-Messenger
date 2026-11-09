package com.offgrid.messenger

/** Offline tests with a virtual main handler and simulated OGM2 acknowledgments. */
private class VirtualClock {
    var ms = 1_000L
    val alarms = linkedMapOf<Runnable,Long>()
    fun schedule(task:Runnable,delay:Long) {alarms[task]=ms+delay}
    fun cancel(task:Runnable){alarms.remove(task)}
    fun advance(delta:Long) {
        val target=ms+delta
        while (true){
            val due=alarms.entries.filter{it.value<=target}.minByOrNull{it.value} ?: break
            ms=due.value
            alarms.remove(due.key)
            due.key.run()
        }
        ms=target
    }
}
fun main(){
    val frames=List(14){byteArrayOf(it.toByte(),(it*7).toByte())}
    val t=VirtualClock()
    val tx=mutableListOf<Int>()
    var finished=0
    var failed=""
    var paused=0
    val progress=mutableListOf<Int>()
    val sender=MediaTransferWindow(frames,3,{t.ms},t::schedule,t::cancel,
        {frame ->tx.add(frame[0].toInt() and 255)},
        {count,_ ->progress.add(count)}, {finished++}, {failed=it}, {paused++})
    sender.startOrResume()
    check(tx==listOf(0,1,2)) {"Must send exactly 3 frames, no more: $tx"}
    check(sender.inFlight==3)
    // QUEUED can arrive out of order; wire IDs differ from frame index.
    check(sender.onQueued(OgmCodec.hex(frames[2]),"4","102"))
    check(sender.onQueued(OgmCodec.hex(frames[0]),"4","100"))
    check(sender.onQueued(OgmCodec.hex(frames[1]),"4","101"))
    check(sender.onDelivered("4","101"))
    check(tx==listOf(0,1,2,3))
    check(sender.onQueued(OgmCodec.hex(frames[3]),"4","103"))
    check(sender.onDelivered("4","102"))
    check(sender.onDelivered("4","100"))
    check(sender.completedCount==3 && tx.takeLast(2)==listOf(4,5))
    // Unknown radio sessions must never mark a fragment delivered.
    check(!sender.onDelivered("5","103"))
    sender.pauseForReceipts()
    check(tx.size==6)
    for(idx in listOf(3,4,5)) {
        if(idx>3)check(sender.onQueued(OgmCodec.hex(frames[idx]),"4",(100+idx).toString()))
        check(sender.onDelivered("4",(100+idx).toString()))
    }
    check(paused==1 && sender.completedCount==6 && sender.inFlight==0)
    sender.startOrResume()
    check(tx.size==9)
    // A failed radio fragment retries the original encrypted bytes only.
    check(sender.onQueued(OgmCodec.hex(frames[6]),"4","206"))
    check(sender.onFailed("4","206"))
    check(tx.last()==6)
    check(sender.onQueued(OgmCodec.hex(frames[6]),"4","306"))
    check(sender.onDelivered("4","306"))
    // Finish remaining frames with scrambled (but matched) IDs.
    val completed=mutableSetOf(0,1,2,3,4,5,6)
    var guard=0
    while(finished==0 && guard++<100){
        val idx=tx.lastOrNull{it !in completed} ?: error("No pending test frame")
        check(sender.onQueued(OgmCodec.hex(frames[idx]),"4",(500+idx).toString()))
        check(sender.onDelivered("4",(500+idx).toString()))
        completed.add(idx)
    }
    check(finished==1 && failed.isEmpty() && sender.completedCount==14)
    check(progress==(1..14).toList())
    check(!sender.onDelivered("4","100"))
    println("PASS: 3-window, out-of-order ACK, receipt pause, resume, retry, completion")

    val timeout=VirtualClock()
    var transmissions=0
    var failure=""
    val bad=MediaTransferWindow(listOf(byteArrayOf(8)),1,{timeout.ms},timeout::schedule,timeout::cancel,
        {transmissions++},{_,_->},{},{failure=it},{})
    bad.startOrResume()
    timeout.advance(23_000)
    check(transmissions==3 && failure.contains("3 attempts"))
    println("PASS: bounded retransmits then terminal failure")

    val ackTime=VirtualClock()
    val ackCommands=mutableListOf<String>()
    val batch=RadioMediaAckBatcher(ackTime::schedule,ackTime::cancel, {ackCommands.add(it)},65L,4)
    val mac="AA:BB:CC:DD:EE:FF"
    for(id in 21..24)batch.accept(mac,"42",id.toString())
    check(ackCommands.single()=="APPACKB $mac 42 21 22 23 24")
    check(ackTime.alarms.isEmpty())
    batch.accept(mac,"43","88")
    batch.accept(mac,"43","88") // duplicated RX must not add a duplicate ACK ID
    ackTime.advance(64)
    check(ackCommands.size==1)
    ackTime.advance(1)
    check(ackCommands[1]=="APPACKB $mac 43 88")
    batch.accept(mac,"44","5")
    batch.clear()
    ackTime.advance(100)
    check(ackCommands.size==2)
    println("PASS: selective radio ACK grouping, timer, duplicate suppression, clearing")
}
