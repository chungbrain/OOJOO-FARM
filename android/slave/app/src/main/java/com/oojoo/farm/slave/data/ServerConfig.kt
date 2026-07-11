package com.oojoo.farm.slave.data

import android.content.Context

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
