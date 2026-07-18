package com.oojoo.farm.master.data

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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

data class ResolvedLocation(
    val lat: Double,
    val lon: Double,
    val source: String, // gps | network | ip
)

/**
 * GPS → Network → IP 순으로 현재 위치를 얻는다.
 * 에뮬레이터처럼 GPS가 없으면 IP 기반으로도 동작한다.
 */
object LocationHelper {

    fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    suspend fun resolve(ctx: Context): ResolvedLocation? {
        if (hasLocationPermission(ctx)) {
            deviceLocation(ctx)?.let { return it }
        }
        return ipLocation()
    }

    @SuppressLint("MissingPermission")
    private suspend fun deviceLocation(ctx: Context): ResolvedLocation? = withContext(Dispatchers.Default) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = listOf(
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

        // 최근 위치가 15분 이내면 사용
        if (last != null && System.currentTimeMillis() - last.time < 15 * 60_000L) {
            val src = if (last.provider == LocationManager.GPS_PROVIDER) "gps" else "network"
            return@withContext ResolvedLocation(last.latitude, last.longitude, src)
        }

        // 짧게 한 번 갱신 시도
        val fresh = withTimeoutOrNull(8_000L) { requestSingleUpdate(lm) }
        if (fresh != null) {
            val src = if (fresh.provider == LocationManager.GPS_PROVIDER) "gps" else "network"
            return@withContext ResolvedLocation(fresh.latitude, fresh.longitude, src)
        }

        last?.let {
            val src = if (it.provider == LocationManager.GPS_PROVIDER) "gps" else "network"
            ResolvedLocation(it.latitude, it.longitude, src)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdate(lm: LocationManager): Location? =
        suspendCancellableCoroutine { cont ->
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> {
                    cont.resume(null)
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

    private suspend fun ipLocation(): ResolvedLocation? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://ipapi.co/json/")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                requestMethod = "GET"
            }
            conn.inputStream.bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val lat = json.optDouble("latitude", Double.NaN)
                val lon = json.optDouble("longitude", Double.NaN)
                if (lat.isFinite() && lon.isFinite()) ResolvedLocation(lat, lon, "ip") else null
            }
        } catch (_: Exception) {
            // secondary fallback
            try {
                val url = URL("http://ip-api.com/json/?fields=status,lat,lon")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 6_000
                }
                conn.inputStream.bufferedReader().use { reader ->
                    val json = JSONObject(reader.readText())
                    if (json.optString("status") != "success") return@withContext null
                    ResolvedLocation(json.getDouble("lat"), json.getDouble("lon"), "ip")
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
