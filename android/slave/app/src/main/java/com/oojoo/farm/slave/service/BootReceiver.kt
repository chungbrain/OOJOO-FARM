package com.oojoo.farm.slave.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.oojoo.farm.slave.data.Prefs

/**
 * 재부팅 후 자동 시작 (PRD 7.2 Kiosk/자동 부팅).
 * 페어링 완료된 farmer 기기는 화면/옵션과 무관하게 부팅 시 되살린다 —
 * farmer는 전담 기기이므로 화면 꺼짐/재부팅 후에도 동작이 요구된다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Prefs.isPaired(context)) {
                FarmerService.start(context)
            }
        }
    }
}
