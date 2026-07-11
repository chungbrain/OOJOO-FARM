package com.oojoo.farm.slave.data

import android.content.Context

object Prefs {
    private const val FILE = "farmer_prefs"
    private const val K_SLAVE = "slaveId"
    private const val K_SESSION = "sessionKey"
    private const val K_USER = "userId"
    private const val K_SERVER = "serverUrl"
    private const val K_REGION = "region"
    private const val K_WEATHER_FACTOR = "weatherFactor"
    private const val K_CAPTURE_INTERVAL = "captureInterval"
    private const val K_AUTO_WATER = "autoWater"
    private const val K_HEADLESS = "headless"
    private const val K_QUEUE = "offlineQueue"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun saveSession(ctx: Context, slaveId: String, sessionKey: String, userId: String) {
        sp(ctx).edit()
            .putString(K_SLAVE, slaveId)
            .putString(K_SESSION, sessionKey)
            .putString(K_USER, userId)
            .apply()
    }

    fun slaveId(ctx: Context) = sp(ctx).getString(K_SLAVE, null)
    fun userId(ctx: Context) = sp(ctx).getString(K_USER, null)
    fun sessionKey(ctx: Context) = sp(ctx).getString(K_SESSION, null)
    fun isPaired(ctx: Context) = slaveId(ctx) != null

    fun clearSession(ctx: Context) {
        sp(ctx).edit().remove(K_SLAVE).remove(K_SESSION).remove(K_USER).apply()
    }

    fun headless(ctx: Context): Boolean = sp(ctx).getBoolean(K_HEADLESS, false)
    fun setHeadless(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(K_HEADLESS, on).apply()
    }

    // 오프라인 이벤트 큐 (JSON 배열 문자열)
    fun offlineQueue(ctx: Context): String = sp(ctx).getString(K_QUEUE, "[]") ?: "[]"
    fun setOfflineQueue(ctx: Context, json: String) {
        sp(ctx).edit().putString(K_QUEUE, json).apply()
    }

    fun serverUrl(ctx: Context): String =
        sp(ctx).getString(K_SERVER, null) ?: (ServerConfig.serverUrl(ctx) ?: "http://10.0.2.2:4000/")

    fun setServerUrl(ctx: Context, url: String) {
        sp(ctx).edit().putString(K_SERVER, url).apply()
    }

    fun region(ctx: Context): String =
        sp(ctx).getString(K_REGION, "Seoul") ?: "Seoul"

    fun setRegion(ctx: Context, region: String) {
        sp(ctx).edit().putString(K_REGION, region).apply()
    }

    fun weatherFactor(ctx: Context): Double =
        sp(ctx).getFloat(K_WEATHER_FACTOR, 1.0f).toDouble()

    fun setWeatherFactor(ctx: Context, factor: Double) {
        sp(ctx).edit().putFloat(K_WEATHER_FACTOR, factor.toFloat()).apply()
    }

    fun captureIntervalMinutes(ctx: Context): Int =
        sp(ctx).getInt(K_CAPTURE_INTERVAL, 60)

    fun setCaptureIntervalMinutes(ctx: Context, minutes: Int) {
        sp(ctx).edit().putInt(K_CAPTURE_INTERVAL, minutes.coerceIn(1, 360)).apply()
    }

    fun autoWater(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_AUTO_WATER, true)

    fun setAutoWater(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(K_AUTO_WATER, on).apply()
    }
}
