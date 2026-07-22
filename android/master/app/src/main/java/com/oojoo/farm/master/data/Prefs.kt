package com.oojoo.farm.master.data

import android.content.Context

object Prefs {
    private const val FILE = "master_prefs"
    private const val K_USER = "userId"
    private const val K_NICK = "nickname"
    private const val K_REGION = "region"
    private const val K_SERVER = "serverUrl"
    private const val K_GALLERY = "galleryItems"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun userId(ctx: Context): String? = sp(ctx).getString(K_USER, null)
    fun nickname(ctx: Context): String? = sp(ctx).getString(K_NICK, null)
    fun region(ctx: Context): String = sp(ctx).getString(K_REGION, "Seoul") ?: "Seoul"

    fun setRegion(ctx: Context, region: String) {
        sp(ctx).edit().putString(K_REGION, region).apply()
    }

    // YAML에서 기본 주소 읽기 → 없으면 에뮬레이터 기본값
    fun defaultServerUrl(ctx: Context): String =
        ServerConfig.serverUrl(ctx) ?: "http://10.0.2.2:4000/"

    fun serverUrl(ctx: Context): String =
        sp(ctx).getString(K_SERVER, null) ?: defaultServerUrl(ctx)

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

    // ---------- 사진첩 (갤러리) ----------
    // JSON 배열 문자열로 저장: [{"path":"...","slaveName":"...","createdAt":"..."}, ...]
    fun galleryItems(ctx: Context): String =
        sp(ctx).getString(K_GALLERY, "[]") ?: "[]"

    fun setGalleryItems(ctx: Context, json: String) {
        sp(ctx).edit().putString(K_GALLERY, json).apply()
    }

    fun addGalleryItem(ctx: Context, path: String, slaveName: String, createdAt: String) {
        val arr = org.json.JSONArray(galleryItems(ctx))
        arr.put(org.json.JSONObject().apply {
            put("path", path)
            put("slaveName", slaveName)
            put("createdAt", createdAt)
        })
        setGalleryItems(ctx, arr.toString())
    }

    // ---------- UI 커스터마이징 ----------
    private const val K_CORNER_RADIUS = "cornerRadius"
    private const val K_SHADOW_OFFSET = "shadowOffset"
    private const val K_BORDER_WIDTH = "borderWidth"

    fun cornerRadius(ctx: Context): Int = sp(ctx).getInt(K_CORNER_RADIUS, 24)
    fun setCornerRadius(ctx: Context, value: Int) = sp(ctx).edit().putInt(K_CORNER_RADIUS, value).apply()

    fun shadowOffset(ctx: Context): Int = sp(ctx).getInt(K_SHADOW_OFFSET, 4)
    fun setShadowOffset(ctx: Context, value: Int) = sp(ctx).edit().putInt(K_SHADOW_OFFSET, value).apply()

    fun borderWidth(ctx: Context): Int = sp(ctx).getInt(K_BORDER_WIDTH, 2)
    fun setBorderWidth(ctx: Context, value: Int) = sp(ctx).edit().putInt(K_BORDER_WIDTH, value).apply()

    // ---------- 언어 설정 ----------
    private const val K_LANGUAGE = "language"
    fun language(ctx: Context): String = sp(ctx).getString(K_LANGUAGE, "system") ?: "system"
    fun setLanguage(ctx: Context, lang: String) {
        sp(ctx).edit().putString(K_LANGUAGE, lang).apply()
    }
}
