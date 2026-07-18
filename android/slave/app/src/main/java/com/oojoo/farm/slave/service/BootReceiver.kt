package com.oojoo.farm.slave.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.oojoo.farm.slave.data.Prefs

/**
 * 재부팅 후 자동 시작 (PRD 7.2 Kiosk/자동 부팅).
 * 페어링 완료 + 헤드리스 모드일 때만 서비스를 되살린다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Prefs.isPaired(context) && Prefs.headless(context)) {
                FarmerService.start(context)
            }
        }
    }
}
