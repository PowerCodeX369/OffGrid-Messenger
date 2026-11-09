package com.offgrid.messenger

import android.content.Context
import java.util.ArrayDeque

/** Process-scoped BLE owner. Prevents a screen rotation from destroying a healthy GATT session.
 * While a foreground service is enabled, the app process remains eligible to receive packets;
 * Android can still terminate it. A bounded backlog bridges short Activity recreation gaps.
 */
object BleSessionHolder {
    private var client:BleNodeClient?=null
    private var activeAddress:String?=null
    private var statusListener:((String,Boolean)->Unit)?=null
    private var lineListener:((String)->Unit)?=null
    private var diagnosticListener:((String)->Unit)?=null
    private var lastStatus="Not connected"
    private var lastReady=false
    private val buffered=ArrayDeque<String>()

    fun attach(context:Context, onStatus:(String,Boolean)->Unit,
               onLine:(String)->Unit, onDiagnostic:(String)->Unit) {
        val app=context.applicationContext
        if(client==null) {
            val secure=SecureKeyStore(app)
            client=BleNodeClient(app,{secure.readPairingKey()},
                { text:String,ok:Boolean -> lastStatus=text;lastReady=ok;statusListener?.invoke(text,ok);Unit },
                { line:String ->
                    if(lineListener!=null) lineListener?.invoke(line) else {
                        if(buffered.size>=2400)buffered.removeFirst()
                        buffered.addLast(line)
                        // Keep the radio acknowledged while the Activity is being recreated.
                        if(line.startsWith("RX|")) {
                            val parts=line.split('|')
                            if(parts.size>=5)client?.send("APPACK ${parts[1]} ${parts[2]} ${parts[3]}")
                        }
                    }
                    Unit
                },
                { entry:String -> diagnosticListener?.invoke(entry);Unit }
            )
        }
        statusListener=onStatus;lineListener=onLine;diagnosticListener=onDiagnostic
        onStatus(lastStatus,lastReady)
        while(buffered.isNotEmpty()) onLine(buffered.removeFirst())
    }
    fun detach(){statusListener=null;lineListener=null;diagnosticListener=null}
    fun connect(address:String) {
        if(lastReady && activeAddress==address)return
        activeAddress=address;client?.connect(address)
    }
    fun send(command:String){client?.send(command)}
    fun sendFrame(frame:ByteArray):Int?=client?.sendFrame(frame)
    fun stop(){client?.stop();activeAddress=null;lastReady=false;lastStatus="Disconnected"}
    val ready get()=lastReady
}
