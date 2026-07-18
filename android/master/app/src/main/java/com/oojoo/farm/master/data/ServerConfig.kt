package com.oojoo.farm.master.data

import android.content.Context

/**
 * assets/server_config.yaml 에서 서버 주소를 읽는다.
 * YAML 라이브러리 없이 단순 파싱 (key: value 형식만 지원).
 */
object ServerConfig {
    private const val FILE = "server_config.yaml"

    fun serverUrl(ctx: Context): String? {
        return try {
            val text = ctx.assets.open(FILE).bufferedReader().use { it.readText() }
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .firstOrNull { it.startsWith("server_url:") }
                ?.substringAfter("server_url:")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }
}
