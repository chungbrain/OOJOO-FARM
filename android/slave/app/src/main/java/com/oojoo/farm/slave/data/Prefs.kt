package com.oojoo.farm.slave.data

import android.content.Context

object Prefs {
    private const val FILE = "farmer_prefs"
    private const val K_SLAVE = "slaveId"
    private const val K_SESSION = "sessionKey"
    private const val K_USER = "userId"
    private const val K_SERVER = "serverUrl"

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
    fun isPaired(ctx: Context) = slaveId(ctx) != null

    fun serverUrl(ctx: Context): String =
        sp(ctx).getString(K_SERVER, "http://10.0.2.2:4000/") ?: "http://10.0.2.2:4000/"

    fun setServerUrl(ctx: Context, url: String) {
        sp(ctx).edit().putString(K_SERVER, url).apply()
    }
}
