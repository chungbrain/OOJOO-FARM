package com.oojoo.farm.master.data

import android.content.Context

/**
 * 앱 실행 중 유지되는 계정 세션(싱글톤).
 * 순수 ViewModel 들이 Context 없이 userId/region 에 접근할 수 있도록 한다.
 * MainActivity onCreate 에서 load() 로 초기화된다.
 */
object Session {
    @Volatile var userId: String = ""
    @Volatile var email: String = ""
    @Volatile var nickname: String = ""
    @Volatile var region: String = "Seoul"

    fun load(ctx: Context) {
        userId = Prefs.userId(ctx) ?: ""
        email = Prefs.email(ctx) ?: ""
        nickname = Prefs.nickname(ctx) ?: ""
        region = Prefs.region(ctx)
    }

    fun set(userId: String, nickname: String, region: String) {
        this.userId = userId
        this.nickname = nickname
        this.region = region
    }

    /** 로그인/회원가입 직후 — email 포함. */
    fun setAuth(userId: String, email: String?, nickname: String?, region: String?) {
        this.userId = userId
        this.email = email ?: ""
        this.nickname = nickname ?: ""
        this.region = region ?: "Seoul"
    }

    fun clear() {
        userId = ""
        email = ""
        nickname = ""
        region = "Seoul"
    }

    fun updateRegion(ctx: Context, region: String) {
        this.region = region
        Prefs.setRegion(ctx, region)
    }
}
