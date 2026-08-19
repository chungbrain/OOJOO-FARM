package com.oojoo.farm.slave.album

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AlbumEntry(
    val file: File,
    val takenAt: String,
    val location: String,
    val plantId: String
)

object PlantAlbum {
    private fun dir(ctx: Context, plantId: String) =
        File(ctx.filesDir, "album/$plantId").apply { mkdirs() }

    private fun indexFile(ctx: Context, plantId: String) = File(dir(ctx, plantId), "index.json")

    fun add(ctx: Context, plantId: String, jpeg: File, takenAt: String, location: String): AlbumEntry? {
        if (!jpeg.exists() || jpeg.length() < 32) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(dir(ctx, plantId), "p_${stamp}.jpg")
        jpeg.copyTo(dest, overwrite = true)
        val entry = AlbumEntry(dest, takenAt, location, plantId)
        val arr = readIndex(ctx, plantId)
        arr.put(JSONObject().apply {
            put("file", dest.name)
            put("takenAt", takenAt)
            put("location", location)
        })
        indexFile(ctx, plantId).writeText(arr.toString())
        return entry
    }

    fun list(ctx: Context, plantId: String): List<AlbumEntry> {
        val arr = readIndex(ctx, plantId)
        val out = mutableListOf<AlbumEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val f = File(dir(ctx, plantId), o.getString("file"))
            if (f.exists()) {
                out.add(AlbumEntry(f, o.optString("takenAt"), o.optString("location"), plantId))
            }
        }
        return out.sortedBy { it.takenAt }
    }

    private fun readIndex(ctx: Context, plantId: String): JSONArray {
        val f = indexFile(ctx, plantId)
        if (!f.exists()) return JSONArray()
        return try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
    }
}
