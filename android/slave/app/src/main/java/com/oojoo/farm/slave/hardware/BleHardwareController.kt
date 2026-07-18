package com.oojoo.farm.slave.hardware

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * ESP32 BLE 하드웨어 컨트롤러. Nordic UART Service(NUS) RX 특성에 명령 문자열을 write 한다.
 *
 * ESP32 펌웨어는 NUS 를 광고하고 다음 명령을 수신하도록 구현한다:
 *   VALVE:ON / VALVE:OFF / FAN:<ms> / LASER:<ms>
 *
 * 권한/블루투스 미가용/미연결 시에는 조용히 무시하며, 상위(FarmerEngine)는
 * 연결 여부와 무관하게 백엔드 관수 로그를 남긴다(오프라인 자율 유지).
 */
class BleHardwareController(private val ctx: Context) : HardwareController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var valveJob: Job? = null
    private var fanJob: Job? = null
    private var scanning = false

    private val adapter: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasPerm(perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun canScan() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        hasPerm(android.Manifest.permission.BLUETOOTH_SCAN) else true

    private fun canConnect() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        hasPerm(android.Manifest.permission.BLUETOOTH_CONNECT) else true

    @SuppressLint("MissingPermission")
    override fun connect() {
        val a = adapter ?: return
        if (!a.isEnabled || !canScan()) { Log.w(TAG, "BLE 스캔 불가(권한/어댑터)"); return }
        val scanner = a.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.i(TAG, "ESP32 BLE 스캔 시작")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val a = adapter ?: return
            if (!scanning) return
            scanning = false
            try { a.bluetoothLeScanner?.stopScan(this) } catch (_: Exception) {}
            if (!canConnect()) return
            Log.i(TAG, "ESP32 발견 → 연결 시도")
            gatt = result.device.connectGatt(ctx, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (canConnect()) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connected.value = false
                rxChar = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            rxChar = svc?.getCharacteristic(RX_CHAR_UUID)
            _connected.value = rxChar != null
            Log.i(TAG, if (rxChar != null) "ESP32 연결 완료" else "NUS 특성 없음")
        }
    }

    @SuppressLint("MissingPermission")
    private fun write(cmd: String) {
        val g = gatt ?: return
        val c = rxChar ?: return
        if (!canConnect()) return
        val bytes = cmd.toByteArray(Charsets.UTF_8)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            Log.w(TAG, "write 실패: ${e.message}")
        }
    }

    override fun openValve(durationMs: Long) {
        val d = durationMs.coerceIn(0, HardwareController.FAILSAFE_MAX_MS)
        write("VALVE:ON")
        valveJob?.cancel()
        valveJob = scope.launch { delay(d); closeValve() }
    }

    override fun closeValve() {
        valveJob?.cancel()
        write("VALVE:OFF")
    }

    override fun fan(durationMs: Long) {
        val d = durationMs.coerceIn(0, HardwareController.FAILSAFE_MAX_MS)
        write("FAN:$d")
        fanJob?.cancel()
        fanJob = scope.launch { delay(d); write("FAN:0") }
    }

    override fun laserPulse(durationMs: Long) {
        write("LASER:${durationMs.coerceIn(0, 2_000L)}")
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        rxChar = null
        _connected.value = false
    }

    companion object {
        private const val TAG = "BleHardware"
        // Nordic UART Service
        private val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    }
}
