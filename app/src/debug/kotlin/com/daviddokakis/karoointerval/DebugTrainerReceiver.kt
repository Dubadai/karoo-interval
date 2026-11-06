package com.daviddokakis.karoointerval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.SavedDevices

/**
 * Debug builds only. SPIKE for indoor ERG: drives [TrainerLink] from adb, e.g.
 * ```
 * adb shell am broadcast -a com.daviddokakis.karoointerval.DEBUG_TRAINER -p com.daviddokakis.karoointerval --es cmd devices
 * … --es cmd open --ei device 28436
 * … --es cmd watts --ei w 150
 * … --es cmd ramp --ei from 120 --ei to 200 --ei s 10
 * … --es cmd res --ei pct 0
 * … --es cmd status
 * … --es cmd close
 * ```
 * Results are in `adb logcat -s ErgSpike`.
 */
class DebugTrainerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")
        Log.i(TAG, "debug cmd=$cmd")
        when (cmd) {
            "devices" -> logSavedDevices(context)
            "open" -> TrainerLink.open(context, intent.getIntExtra("device", 0))
            "watts" -> TrainerLink.setWatts(intent.getIntExtra("w", 0))
            "ramp" -> TrainerLink.ramp(intent.getIntExtra("from", 0), intent.getIntExtra("to", 0), intent.getIntExtra("s", 10).coerceAtLeast(1))
            "res" -> TrainerLink.setResistance(intent.getIntExtra("pct", 0))
            "status" -> TrainerLink.requestStatus()
            "close" -> TrainerLink.close()
            "probepower" -> TrainerLink.probePower(context, intent.getIntExtra("device", 0))
            // Bluetooth FTMS
            "blescan" -> FtmsLink.scan(context, intent.getIntExtra("s", 15))
            "bleconnect" -> FtmsLink.connect(context, intent.getStringExtra("addr") ?: "")
            "blecontrol" -> FtmsLink.requestControl()
            "blestart" -> FtmsLink.start()
            "blewatts" -> FtmsLink.setWatts(intent.getIntExtra("w", 0))
            "bleramp" -> FtmsLink.ramp(intent.getIntExtra("from", 0), intent.getIntExtra("to", 0), intent.getIntExtra("s", 10).coerceAtLeast(1))
            "blereset" -> FtmsLink.reset()
            "bleclose" -> FtmsLink.close()
            else -> Log.w(TAG, "unknown cmd $cmd")
        }
    }

    /** Lists the sensors paired to the Karoo (to see how the trainer shows up). */
    private fun logSavedDevices(context: Context) {
        val karoo = KarooSystemService(context.applicationContext)
        karoo.connect { connected ->
            if (!connected) return@connect
            karoo.addConsumer<SavedDevices> { e ->
                e.devices.forEach { d ->
                    Log.i(TAG, "saved id=${d.id} conn=${d.connectionType} name=${d.name} enabled=${d.enabled} maker=${d.details.manufacturer} types=${d.supportedDataTypes}")
                }
                karoo.disconnect()
            }
        }
    }

    private companion object { const val TAG = "ErgSpike" }
}
