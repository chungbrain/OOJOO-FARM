package com.oojoo.farm.master.data

import android.content.Context

object Prefs {
    private const val FILE = "master_prefs"
    private const val K_USER = "userId"
    private const val K_NICK = "nickname"
    private const val K_REGION = "region"
    private const val K_SERVER = "serverUrl"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun userId(ctx: Context): String? = sp(ctx).getString(K_USER, null)
    fun nickname(ctx: Context): String? = sp(ctx).getString(K_NICK, null)
    fun region(ctx: Context): String = sp(ctx).getString(K_REGION, "Seoul") ?: "Seoul"
    fun serverUrl(ctx: Context): String =
        sp(ctx).getString(K_SERVER, "http://10.0.2.2:4000/") ?: "http://10.0.2.2:4000/"

    fun isOnboarded(ctx: Context) = !userId(ctx).isNullOrBlank()

    fun saveAccount(ctx: Context, userId: String, nickname: String?, region: String) {
        sp(ctx).edit()
            .putString(K_USER, userId)
            .putString(K_NICK, nickname)
            .putString(K_REGION, region)
            .apply()
    }

    fun setServerUrl(ctx: Context, url: String) {
        sp(ctx).edit().putString(K_SERVER, url).apply()
    }
}
