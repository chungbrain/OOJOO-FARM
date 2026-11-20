package com.oojoo.farm.slave.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Farmer(농장) 위치 해석 — 마스터 홈의 "농장 날씨" 조회 데이터.
 *
 * GPS → network → passive 의 last-known(최신 우선)을 쓰고, 30분 이상
 * 오래되면 8초 한도로 single-update 시도, 그래도 없으면 오래된
 * last-known 을 쓴다. 농장은 움직이지 않으므로 오래된 위치도
 * 무조건 null 보다 낫다. (IP 폴백 없음 — 헤드리스 기기 부담 방지.)
 */
object LocationUtil {

    data class Loc(val lat: Double, val lon: Double)

    private const val FRESH_MS = 30 * 60_000L
    private const val UPDATE_TIMEOUT_MS = 8_000L

    fun hasPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    suspend fun resolve(ctx: Context): Loc? {
        if (!hasPermission(ctx)) return null
        return withContext(Dispatchers.Default) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withContext null
            val last = latestKnown(lm)
            if (last != null && System.currentTimeMillis() - last.time < FRESH_MS) {
                return@withContext Loc(last.latitude, last.longitude)
            }
            val fresh = withTimeoutOrNull(UPDATE_TIMEOUT_MS) { requestSingleUpdate(lm) }
            if (fresh != null) return@withContext Loc(fresh.latitude, fresh.longitude)
            last?.let { Loc(it.latitude, it.longitude) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun latestKnown(lm: LocationManager): Location? = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    ).mapNotNull { provider ->
        try {
            if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
        } catch (_: Exception) {
            null
        }
    }.maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdate(lm: LocationManager): Location? =
        suspendCancellableCoroutine { cont ->
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
            }
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    try { lm.removeUpdates(this) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            cont.invokeOnCancellation {
                try { lm.removeUpdates(listener) } catch (_: Exception) {}
            }
            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
}
