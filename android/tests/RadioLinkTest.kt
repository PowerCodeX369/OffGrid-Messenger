import com.offgrid.messenger.RadioLink

fun main() {
    val normal=RadioLink.parse("LINK|1C:DB:D4:3B:00:FC|RSSI=-45|PHY=12|MODE=AUTO|CHANGES=2|PHYMODE=OFDM12|REGAGE=600")!!
    check(normal.signalLabel=="-45 dBm")
    check(normal.phyLabel=="12 Mbps")
    check(normal.rateMode=="AUTO" && normal.changes==2L)
    val lr=RadioLink.parse("LINK|AA:BB:CC:DD:EE:FF|RSSI=-58|PHY=0.25|MODE=LR250|PHYMODE=LR250|REGAGE=250")!!
    check(lr.summary.contains("LR 250 kbps") && lr.rateMode=="LR250")
    val lost=RadioLink.parse("LINK|AA:BB:CC:DD:EE:FF|RSSI=-58|PHY=0.5|MODE=AUTO|REGAGE=20000")!!
    check(lost.signalLabel=="Peer not heard")
    val absent=RadioLink.parse("LINK|BROADCAST|RSSI=0|PHY=1|MODE=SPEED")!!
    check(absent.rssiDbm==null)
    check(RadioLink.parse("READY|node")==null)
    check(RadioLink.parse("LINK||RSSI=-50")==null)
    check(RadioLink.choices==listOf("AUTO", "SPEED", "RANGE", "LR500", "LR250"))
    println("PASS: RF telemetry parse / stale peer / manual modes")
}
