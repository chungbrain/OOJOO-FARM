package com.oojoo.farm.slave.hardware

import android.content.Context

/**
 * 현재 사용 중인 하드웨어 컨트롤러 제공자.
 * 기본값은 시뮬레이션([MockHardwareController]). BLE 페어링 후 [useBle] 로 전환한다.
 */
object Hardware {
    @Volatile
    var controller: HardwareController = MockHardwareController
        private set

    fun useBle(ctx: Context) {
        val ble = BleHardwareController(ctx.applicationContext)
        controller = ble
        ble.connect()
    }

    fun useMock() {
        controller = MockHardwareController
    }
}
