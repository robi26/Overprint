package ch.steigis.overprint

import android.content.Context
import android.os.PowerManager

/** Keeps the CPU awake for a Garmin sync so the radio is not dropped on idle. */
class SyncWakeLock(context: Context) {
    private val appContext = context.applicationContext
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire() {
        if (lock?.isHeld == true) return
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val next = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "overprint:garmin-sync")
        next.setReferenceCounted(false)
        next.acquire(MAX_HOLD_MS)
        lock = next
        GarminSyncService.start(appContext)
    }

    @Synchronized
    fun release() {
        lock?.let { held ->
            if (held.isHeld) held.release()
        }
        lock = null
        GarminSyncService.stop(appContext)
    }

    companion object {
        private const val MAX_HOLD_MS = 60L * 60L * 1000L
    }
}
