package android.os
object Build {object VERSION {const val SDK_INT:Int=36}}
class Looper{companion object{fun getMainLooper():Looper=Looper()}}
class Handler(l:Looper){fun post(r:Runnable):Boolean {r.run();return true};fun postDelayed(r:Runnable,ms:Long):Boolean=true;fun removeCallbacks(r:Runnable){} }
object SystemClock {fun elapsedRealtime():Long=0L}
