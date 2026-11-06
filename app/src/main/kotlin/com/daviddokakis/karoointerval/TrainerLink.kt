package com.daviddokakis.karoointerval

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dsi.ant.plugins.antplus.pcc.AntPlusFitnessEquipmentPcc
import com.dsi.ant.plugins.antplus.pcc.AntPlusFitnessEquipmentPcc.EquipmentType
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import java.math.BigDecimal

/**
 * SPIKE (indoor ERG, see docs/design/indoor-erg): talks to an ANT+ FE-C trainer through the
 * ANT+ Plugin Service, next to the Karoo's own connection. Only driven from the debug receiver
 * for now; everything is logged under the tag "ErgSpike".
 *
 * All plugin calls run on the main thread.
 */
object TrainerLink {
    private const val TAG = "ErgSpike"
    private val main = Handler(Looper.getMainLooper())

    private var handle: PccReleaseHandle<AntPlusFitnessEquipmentPcc>? = null
    @Volatile private var pcc: AntPlusFitnessEquipmentPcc? = null
    private var trainerSubscribed = false
    private var rawCount = 0L
    private var rampStep: Runnable? = null

    fun open(context: Context, deviceNumber: Int) = main.post {
        closeNow()
        val app = context.applicationContext
        Log.i(TAG, "ANT+ plugins installed: ${AntPluginPcc.getInstalledPluginsVersionString(app)}; opening FE-C $deviceNumber")
        handle = AntPlusFitnessEquipmentPcc.requestNewOpenAccess(
            app, deviceNumber, 0,
            { result, code, state ->
                Log.i(TAG, "access result=$code initialState=$state")
                when (code) {
                    RequestAccessResult.SUCCESS -> {
                        pcc = result
                        Log.i(TAG, "connected to ${result.deviceName} #${result.antDeviceNumber}")
                    }
                    RequestAccessResult.DEPENDENCY_NOT_INSTALLED ->
                        Log.w(TAG, "missing ${AntPluginPcc.getMissingDependencyName()} (${AntPluginPcc.getMissingDependencyPackageName()})")
                    else -> {}
                }
            },
            { state -> Log.i(TAG, "device state $state") },
            { _, _, type, state ->
                Log.i(TAG, "FE type=$type state=$state")
                if (type == EquipmentType.TRAINER && !trainerSubscribed) subscribeTrainer()
            },
        )
    }

    private fun subscribeTrainer() {
        val p = pcc ?: return
        trainerSubscribed = true
        p.subscribeCapabilitiesEvent { _, _, c ->
            Log.i(TAG, "capabilities targetPower=${c.targetPowerModeSupport} basicResistance=${c.basicResistanceModeSupport} sim=${c.simulationModeSupport} maxResistance=${c.maximumResistance}N")
        }
        val t = p.trainerMethods
        t.subscribeRawTrainerDataEvent { _, _, _, cadence, power, _ ->
            // Every 4th page (≈ 1 s) is enough to see what the trainer does.
            if (rawCount++ % 4 == 0L) Log.i(TAG, "trainer power=${power}W cadence=$cadence")
        }
        t.subscribeTrainerStatusEvent { _, _, flags -> Log.i(TAG, "trainer status $flags") }
        t.subscribeCommandStatusEvent { _, _, s ->
            val data = when (s.lastReceivedCommandId?.name) {
                "TARGET_POWER" -> "targetPower=${s.targetPower}W"
                "BASIC_RESISTANCE" -> "resistance=${s.totalResistance}%"
                "TRACK_RESISTANCE" -> "grade=${s.grade}% crr=${s.rollingResistanceCoefficient}"
                "WIND_RESISTANCE" -> "wind=${s.windSpeed}km/h"
                else -> ""
            }
            Log.i(TAG, "command status lastCmd=${s.lastReceivedCommandId} seq=${s.lastReceivedSequenceNumber} status=${s.status} $data")
        }
        t.subscribeTargetPowerEvent { _, _, w -> Log.i(TAG, "target power page ${w}W") }
        t.subscribeBasicResistanceEvent { _, _, r -> Log.i(TAG, "basic resistance page $r%") }
        Log.i(TAG, "subscribed to trainer events")
    }

    fun setWatts(watts: Int) = main.post { sendWatts(watts) }

    private fun sendWatts(watts: Int) {
        val w = watts.coerceIn(0, MAX_WATTS)
        val ok = pcc?.trainerMethods?.requestSetTargetPower(BigDecimal(w)) { r -> Log.i(TAG, "set target ${w}W -> $r") }
        Log.i(TAG, "send target ${w}W submitted=$ok")
    }

    fun setResistance(percent: Int) = main.post {
        val pct = percent.coerceIn(0, 100)
        val ok = pcc?.trainerMethods?.requestSetBasicResistance(BigDecimal(pct)) { r -> Log.i(TAG, "set resistance $pct% -> $r") }
        Log.i(TAG, "send resistance $pct% submitted=$ok")
    }

    /** Asks the trainer for page 71 (last command it received, from anyone). */
    fun requestStatus() = main.post {
        val ok = pcc?.trainerMethods?.requestCommandStatus({ r -> Log.i(TAG, "command status request -> $r") }, null)
        Log.i(TAG, "request command status submitted=$ok")
    }

    /** Linear ramp, one target a second (the soft start after a pause). */
    fun ramp(from: Int, to: Int, seconds: Int) = main.post {
        rampStep?.let(main::removeCallbacks)
        var i = 0
        val step = object : Runnable {
            override fun run() {
                sendWatts(from + (to - from) * i / seconds)
                if (i++ < seconds) main.postDelayed(this, 1000) else rampStep = null
            }
        }
        rampStep = step
        step.run()
    }

    /** Diagnostic: can the plugin find the trainer's power broadcast (device type 11)? */
    fun probePower(context: Context, deviceNumber: Int) = main.post {
        Log.i(TAG, "probing bike power $deviceNumber")
        var h: PccReleaseHandle<com.dsi.ant.plugins.antplus.pcc.AntPlusBikePowerPcc>? = null
        h = com.dsi.ant.plugins.antplus.pcc.AntPlusBikePowerPcc.requestAccess(
            context.applicationContext, deviceNumber, 0,
            { result, code, state ->
                Log.i(TAG, "power probe result=$code state=$state name=${result?.deviceName}")
                main.postDelayed({ h?.close() }, 3000)
            },
            { state -> Log.i(TAG, "power probe device state $state") },
        )
    }

    fun close() = main.post { closeNow() }

    private fun closeNow() {
        rampStep?.let(main::removeCallbacks)
        rampStep = null
        handle?.close()
        handle = null
        pcc = null
        trainerSubscribed = false
    }

    /** Never send more than this, whatever asks for it. */
    private const val MAX_WATTS = 400
}
