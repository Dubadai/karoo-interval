package com.daviddokakis.karoointerval

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * SPIKE (indoor ERG, see docs/design/indoor-erg): controls a smart trainer over Bluetooth FTMS
 * (Fitness Machine Service). Driven from the debug receiver; logs under "ErgSpike".
 *
 * GATT operations are queued and run one at a time; everything runs on the main thread.
 */
@SuppressLint("MissingPermission")
object FtmsLink {
    private const val TAG = "ErgSpike"
    private val main = Handler(Looper.getMainLooper())

    private fun uuid16(v: Int) = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", v))
    private val FTMS = uuid16(0x1826)
    private val FEATURE = uuid16(0x2ACC)
    private val INDOOR_BIKE_DATA = uuid16(0x2AD2)
    private val POWER_RANGE = uuid16(0x2AD8)
    private val CONTROL_POINT = uuid16(0x2AD9)
    private val STATUS = uuid16(0x2ADA)
    private val TRAINING_STATUS = uuid16(0x2AD3)
    private val CCCD = uuid16(0x2902)

    private var gatt: BluetoothGatt? = null
    private var scanCb: ScanCallback? = null
    private val queue = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var busy = false
    private var bikeCount = 0L
    private var rampStep: Runnable? = null

    // ---- scan ----------------------------------------------------------------------------------

    fun scan(context: Context, seconds: Int = 15) = main.post {
        val scanner = adapter(context)?.bluetoothLeScanner ?: run { Log.w(TAG, "ble: no scanner (Bluetooth off?)"); return@post }
        stopScan(context)
        val seen = mutableSetOf<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                if (seen.add(r.device.address)) Log.i(TAG, "ble: found ${r.device.address} name=${r.scanRecord?.deviceName ?: r.device.name} rssi=${r.rssi}")
            }
            override fun onScanFailed(code: Int) { Log.w(TAG, "ble: scan failed $code") }
        }
        scanCb = cb
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(FTMS)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        Log.i(TAG, "ble: scanning for FTMS for ${seconds}s")
        main.postDelayed({ stopScan(context); Log.i(TAG, "ble: scan done") }, seconds * 1000L)
    }

    private fun stopScan(context: Context) {
        scanCb?.let { adapter(context)?.bluetoothLeScanner?.stopScan(it) }
        scanCb = null
    }

    private fun adapter(context: Context) =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // ---- connect -------------------------------------------------------------------------------

    fun connect(context: Context, address: String) = main.post {
        closeNow()
        val device = adapter(context)?.getRemoteDevice(address) ?: return@post
        Log.i(TAG, "ble: connecting to $address")
        gatt = device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = main.post {
            Log.i(TAG, "ble: connection status=$status state=${if (newState == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else newState}")
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else { queue.clear(); busy = false }
        }.let { }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = main.post {
            val svc = g.getService(FTMS)
            Log.i(TAG, "ble: services status=$status ftms=${svc != null} chars=${svc?.characteristics?.map { "%04x".format(it.uuid.mostSignificantBits.shr(32).toInt() and 0xFFFF) }}")
            if (svc == null) return@post
            enqueue { it.readCharacteristic(svc.getCharacteristic(FEATURE)) }
            svc.getCharacteristic(POWER_RANGE)?.let { c -> enqueue { it.readCharacteristic(c) } }
            for (u in listOf(CONTROL_POINT, STATUS, INDOOR_BIKE_DATA, TRAINING_STATUS)) {
                val c = svc.getCharacteristic(u) ?: continue
                enqueue { gg ->
                    gg.setCharacteristicNotification(c, true)
                    val d = c.getDescriptor(CCCD) ?: return@enqueue false
                    d.value = if (u == CONTROL_POINT) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gg.writeDescriptor(d)
                }
            }
        }.let { }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = main.post {
            val v = c.value ?: ByteArray(0)
            when (c.uuid) {
                FEATURE -> {
                    val target = if (v.size >= 8) u32(v, 4) else 0L
                    Log.i(TAG, "ble: feature raw=${hex(v)} powerTargetSupported=${target and 0x08 != 0L}")
                }
                POWER_RANGE -> Log.i(TAG, "ble: power range ${s16(v, 0)}..${s16(v, 2)} W step ${u16(v, 4)}")
                else -> Log.i(TAG, "ble: read ${c.uuid} ${hex(v)}")
            }
            next()
        }.let { }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = main.post {
            Log.i(TAG, "ble: subscribed ${short(d.characteristic.uuid)} status=$status")
            next()
        }.let { }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = main.post {
            Log.i(TAG, "ble: wrote ${short(c.uuid)} status=$status")
            next()
        }.let { }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            when (c.uuid) {
                CONTROL_POINT -> Log.i(TAG, "ble: control point response op=0x%02x result=%s".format(v.getOrNull(1) ?: -1, result(v.getOrNull(2))))
                STATUS -> Log.i(TAG, "ble: machine status ${status(v)}")
                TRAINING_STATUS -> Log.i(TAG, "ble: training status ${hex(v)}")
                INDOOR_BIKE_DATA -> if (bikeCount++ % 4 == 0L) Log.i(TAG, "ble: bike data ${bikeData(v)}")
            }
        }
    }

    // ---- control -------------------------------------------------------------------------------

    fun requestControl() = main.post { writeCp(byteArrayOf(0x00)) }
    fun start() = main.post { writeCp(byteArrayOf(0x07)) }
    fun reset() = main.post { writeCp(byteArrayOf(0x01)) }
    fun setWatts(watts: Int) = main.post { sendWatts(watts) }

    private fun sendWatts(watts: Int) {
        val w = watts.coerceIn(0, MAX_WATTS)
        Log.i(TAG, "ble: send target ${w}W")
        writeCp(byteArrayOf(0x05, (w and 0xFF).toByte(), (w shr 8 and 0xFF).toByte()))
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

    private fun writeCp(bytes: ByteArray) {
        val c = gatt?.getService(FTMS)?.getCharacteristic(CONTROL_POINT) ?: return Log.w(TAG, "ble: not connected").let { }
        enqueue { g ->
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = bytes
            g.writeCharacteristic(c)
        }
    }

    fun close() = main.post { closeNow() }

    private fun closeNow() {
        rampStep?.let(main::removeCallbacks)
        rampStep = null
        queue.clear(); busy = false
        gatt?.disconnect(); gatt?.close(); gatt = null
    }

    // ---- queue ---------------------------------------------------------------------------------

    private fun enqueue(op: (BluetoothGatt) -> Boolean) { queue.addLast(op); if (!busy) next() else Unit }

    private fun next() {
        busy = false
        val g = gatt ?: return
        while (queue.isNotEmpty()) {
            val op = queue.removeFirst()
            if (op(g)) { busy = true; return }
            Log.w(TAG, "ble: gatt op refused, skipping")
        }
    }

    // ---- decoding ------------------------------------------------------------------------------

    private fun u16(v: ByteArray, i: Int) = if (v.size >= i + 2) (v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8) else -1
    private fun s16(v: ByteArray, i: Int) = if (v.size >= i + 2) ((v[i].toInt() and 0xFF) or (v[i + 1].toInt() shl 8)) else -1
    private fun u32(v: ByteArray, i: Int) = (0..3).fold(0L) { acc, k -> acc or ((v[i + k].toLong() and 0xFF) shl (8 * k)) }
    private fun hex(v: ByteArray) = v.joinToString(" ") { "%02x".format(it) }
    private fun short(u: UUID) = "%04x".format((u.mostSignificantBits shr 32).toInt() and 0xFFFF)

    private fun result(b: Byte?) = when (b?.toInt()) { 1 -> "SUCCESS"; 2 -> "NOT_SUPPORTED"; 3 -> "INVALID_PARAM"; 4 -> "FAILED"; 5 -> "NOT_PERMITTED"; else -> "?$b" }

    private fun status(v: ByteArray): String = when (v.getOrNull(0)?.toInt()) {
        0x01 -> "reset"
        0x02 -> "stopped/paused by user"
        0x04 -> "started/resumed by user"
        0x05 -> "target speed changed"
        0x06 -> "target incline changed"
        0x07 -> "target resistance changed ${hex(v)}"
        0x08 -> "TARGET POWER CHANGED ${s16(v, 1)}W"
        0x12 -> "indoor bike simulation changed ${hex(v)}"
        0xFF -> "control permission lost"
        else -> hex(v)
    }

    /** Indoor Bike Data: flags, then fields in order; we only want cadence and power. */
    private fun bikeData(v: ByteArray): String {
        val f = u16(v, 0); var i = 2
        if (f and 0x01 == 0) i += 2               // instantaneous speed (present when bit 0 is 0)
        if (f and 0x02 != 0) i += 2               // average speed
        val cadence = if (f and 0x04 != 0) u16(v, i).also { i += 2 } / 2 else null
        if (f and 0x08 != 0) i += 2               // average cadence
        if (f and 0x10 != 0) i += 3               // total distance
        if (f and 0x20 != 0) i += 2               // resistance level
        val power = if (f and 0x40 != 0) s16(v, i) else null
        return "power=${power}W cadence=$cadence"
    }

    /** Never send more than this, whatever asks for it. */
    private const val MAX_WATTS = 400
}
