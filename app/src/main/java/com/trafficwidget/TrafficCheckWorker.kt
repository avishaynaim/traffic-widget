package com.trafficwidget

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TrafficCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(
                    TrafficWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE
                )

                val apiKey = prefs.getString(TrafficWidgetProvider.KEY_API_KEY, TrafficWidgetProvider.DEFAULT_API_KEY)
                val direction = prefs.getInt(TrafficWidgetProvider.KEY_DIRECTION, TrafficWidgetProvider.DIRECTION_TO_WORK)

                var homeLat = prefs.getString(TrafficWidgetProvider.KEY_HOME_LAT, null)
                var homeLng = prefs.getString(TrafficWidgetProvider.KEY_HOME_LNG, null)
                var workLat = prefs.getString(TrafficWidgetProvider.KEY_WORK_LAT, null)
                var workLng = prefs.getString(TrafficWidgetProvider.KEY_WORK_LNG, null)

                // Geocode home if missing
                if ((homeLat.isNullOrEmpty() || homeLng.isNullOrEmpty()) && apiKey != null) {
                    val addr = prefs.getString(TrafficWidgetProvider.KEY_HOME_ADDRESS, TrafficWidgetProvider.DEFAULT_HOME_ADDRESS)
                    if (!addr.isNullOrEmpty()) {
                        val coords = geocodeAddress(addr, apiKey)
                        if (coords != null) {
                            homeLat = coords.first.toString(); homeLng = coords.second.toString()
                            prefs.edit().putString(TrafficWidgetProvider.KEY_HOME_LAT, homeLat)
                                .putString(TrafficWidgetProvider.KEY_HOME_LNG, homeLng).apply()
                        }
                    }
                }

                // Geocode work if missing
                if ((workLat.isNullOrEmpty() || workLng.isNullOrEmpty()) && apiKey != null) {
                    val addr = prefs.getString(TrafficWidgetProvider.KEY_WORK_ADDRESS, TrafficWidgetProvider.DEFAULT_WORK_ADDRESS)
                    if (!addr.isNullOrEmpty()) {
                        val coords = geocodeAddress(addr, apiKey)
                        if (coords != null) {
                            workLat = coords.first.toString(); workLng = coords.second.toString()
                            prefs.edit().putString(TrafficWidgetProvider.KEY_WORK_LAT, workLat)
                                .putString(TrafficWidgetProvider.KEY_WORK_LNG, workLng).apply()
                        }
                    }
                }

                // Fixed routes: TO_WORK = Home→Work, TO_HOME = Work→Home
                val originLat: String?; val originLng: String?
                val destLat: String?;   val destLng: String?
                if (direction == TrafficWidgetProvider.DIRECTION_TO_WORK) {
                    originLat = homeLat; originLng = homeLng; destLat = workLat; destLng = workLng
                } else {
                    originLat = workLat; originLng = workLng; destLat = homeLat; destLng = homeLng
                }

                // GPS mode: override origin with current device location
                val useGps = prefs.getBoolean(TrafficWidgetProvider.KEY_USE_GPS, false)
                var finalOriginLat = originLat
                var finalOriginLng = originLng
                if (useGps) {
                    val hasPerm = context.checkSelfPermission(
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasPerm) {
                        try {
                            val lm = context.getSystemService(Context.LOCATION_SERVICE)
                                as android.location.LocationManager
                            @Suppress("MissingPermission")
                            val loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                                ?: lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            if (loc != null) {
                                finalOriginLat = loc.latitude.toString()
                                finalOriginLng = loc.longitude.toString()
                                Log.i(TAG, "GPS origin: $finalOriginLat, $finalOriginLng")
                            }
                        } catch (e: Exception) { Log.e(TAG, "GPS location error", e) }
                    }
                }

                if (apiKey.isNullOrEmpty() || finalOriginLat.isNullOrEmpty() || finalOriginLng.isNullOrEmpty()
                    || destLat.isNullOrEmpty() || destLng.isNullOrEmpty()) {
                    val msg = when {
                        apiKey.isNullOrEmpty() -> "API key not configured"
                        homeLat.isNullOrEmpty() -> "Home address not configured"
                        workLat.isNullOrEmpty() -> "Work address not configured"
                        else -> "Addresses not configured"
                    }
                    prefs.edit().putString(TrafficWidgetProvider.KEY_LAST_ERROR, msg)
                        .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
                    TrafficWidgetProvider.updateAllWidgets(context)
                    return@withContext Result.success()
                }

                val result = fetchTrafficData(apiKey, finalOriginLat.toDouble(), finalOriginLng.toDouble(),
                    destLat.toDouble(), destLng.toDouble())

                if (result != null) {
                    val primary = result.primary
                    val alt = result.alt

                    val ratio = primary.durationInTraffic.toFloat() / primary.duration.toFloat()
                    val status = when {
                        ratio < TrafficWidgetProvider.THRESHOLD_GREEN -> TrafficStatus.GREEN
                        ratio < TrafficWidgetProvider.THRESHOLD_YELLOW -> TrafficStatus.YELLOW
                        else -> TrafficStatus.RED
                    }

                    val editor = prefs.edit()
                        .putInt(TrafficWidgetProvider.KEY_LAST_TRAFFIC_STATUS, status.ordinal)
                        .putInt(TrafficWidgetProvider.KEY_LAST_DURATION, primary.duration)
                        .putInt(TrafficWidgetProvider.KEY_LAST_DURATION_TRAFFIC, primary.durationInTraffic)
                        .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                        .remove(TrafficWidgetProvider.KEY_LAST_ERROR)

                    if (alt != null) {
                        editor.putInt(TrafficWidgetProvider.KEY_LAST_ALT_DURATION, alt.duration)
                              .putInt(TrafficWidgetProvider.KEY_LAST_ALT_DURATION_TRAFFIC, alt.durationInTraffic)
                    } else {
                        editor.putInt(TrafficWidgetProvider.KEY_LAST_ALT_DURATION, 0)
                              .putInt(TrafficWidgetProvider.KEY_LAST_ALT_DURATION_TRAFFIC, 0)
                    }
                    editor.apply()

                    // Download and save static map image
                    downloadRouteMap(
                        apiKey = apiKey,
                        originLat = finalOriginLat.toDouble(), originLng = finalOriginLng.toDouble(),
                        destLat = destLat.toDouble(), destLng = destLng.toDouble(),
                        primaryPolyline = primary.polyline,
                        altPolyline = alt?.polyline
                    )
                }

                TrafficWidgetProvider.updateAllWidgets(context)
                Result.success()

            } catch (e: Exception) {
                Log.e(TAG, "Error in doWork", e)
                val prefs = context.getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                val msg = when (e) {
                    is java.net.UnknownHostException -> "No internet connection"
                    is java.net.SocketTimeoutException -> "Request timeout"
                    else -> "Error: ${e.message ?: "Unknown"}"
                }
                prefs.edit().putString(TrafficWidgetProvider.KEY_LAST_ERROR, msg)
                    .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
                TrafficWidgetProvider.updateAllWidgets(context)
                Result.retry()
            }
        }
    }

    private suspend fun geocodeAddress(address: String, apiKey: String): Pair<Double, Double>? {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(address, "UTF-8")
                val conn = URL("https://maps.googleapis.com/maps/api/geocode/json?address=$encoded&key=$apiKey").openConnection()
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                val json = JSONObject(conn.getInputStream().bufferedReader().readText())
                if (json.getString("status") != "OK") return@withContext null
                val loc = json.getJSONArray("results").getJSONObject(0)
                    .getJSONObject("geometry").getJSONObject("location")
                Pair(loc.getDouble("lat"), loc.getDouble("lng"))
            } catch (e: Exception) { Log.e(TAG, "Geocode error", e); null }
        }
    }

    private suspend fun fetchTrafficData(
        apiKey: String,
        originLat: Double, originLng: Double,
        destLat: Double, destLng: Double
    ): RouteResult? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://routes.googleapis.com/directions/v2:computeRoutes")
            val body = JSONObject().apply {
                put("origin", JSONObject().apply {
                    put("location", JSONObject().apply {
                        put("latLng", JSONObject().apply {
                            put("latitude", originLat); put("longitude", originLng)
                        })
                    })
                })
                put("destination", JSONObject().apply {
                    put("location", JSONObject().apply {
                        put("latLng", JSONObject().apply {
                            put("latitude", destLat); put("longitude", destLng)
                        })
                    })
                })
                put("travelMode", "DRIVE")
                put("routingPreference", "TRAFFIC_AWARE")
                put("computeAlternativeRoutes", true)
                put("languageCode", "en-US")
                put("units", "METRIC")
            }

            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Goog-Api-Key", apiKey)
            conn.setRequestProperty("X-Goog-FieldMask",
                "routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline")
            conn.doOutput = true; conn.connectTimeout = 15000; conn.readTimeout = 15000
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()); it.flush() }

            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "Routes API ${conn.responseCode}: $err")
                val prefs = context.getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                val msg = try { JSONObject(err ?: "{}").optJSONObject("error")?.optString("message", "API error") } catch (e: Exception) { "API error" }
                prefs.edit().putString(TrafficWidgetProvider.KEY_LAST_ERROR, "API: $msg")
                    .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
                return@withContext null
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val routes = json.optJSONArray("routes") ?: return@withContext null
            if (routes.length() == 0) return@withContext null

            fun parseRoute(r: org.json.JSONObject): TrafficData {
                val durationInTraffic = r.optString("duration", "0s").removeSuffix("s").toIntOrNull() ?: 0
                val duration = r.optString("staticDuration", "0s").removeSuffix("s").toIntOrNull() ?: durationInTraffic
                val polyline = r.optJSONObject("polyline")?.optString("encodedPolyline")
                return TrafficData(
                    duration = if (duration > 0) duration else durationInTraffic,
                    durationInTraffic = durationInTraffic,
                    distance = r.optInt("distanceMeters", 0),
                    polyline = polyline
                )
            }

            val primary = parseRoute(routes.getJSONObject(0))
            val alt = if (routes.length() > 1) parseRoute(routes.getJSONObject(1)) else null

            RouteResult(primary, alt)
        } catch (e: Exception) {
            Log.e(TAG, "fetchTrafficData error", e); null
        }
    }

    private suspend fun downloadRouteMap(
        apiKey: String,
        originLat: Double, originLng: Double,
        destLat: Double, destLng: Double,
        primaryPolyline: String?,
        altPolyline: String?
    ) = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder("https://maps.googleapis.com/maps/api/staticmap?")
            sb.append("size=640x400")
            sb.append("&maptype=roadmap")

            // Alt route (drawn first, underneath)
            if (!altPolyline.isNullOrEmpty()) {
                sb.append("&path=weight:3|color:0x9E9E9Eaa|enc:${URLEncoder.encode(altPolyline, "UTF-8")}")
            }

            // Primary route
            if (!primaryPolyline.isNullOrEmpty()) {
                sb.append("&path=weight:5|color:0x1A73E8ff|enc:${URLEncoder.encode(primaryPolyline, "UTF-8")}")
            }

            // Start and end markers
            sb.append("&markers=size:mid|color:green|label:S|${originLat},${originLng}")
            sb.append("&markers=size:mid|color:red|label:E|${destLat},${destLng}")
            sb.append("&key=${apiKey}")

            val conn = URL(sb.toString()).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 15000

            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.readBytes()
                val file = File(context.filesDir, TrafficWidgetProvider.MAP_FILE_NAME)
                FileOutputStream(file).use { it.write(bytes) }
                Log.i(TAG, "Map saved: ${bytes.size} bytes")
            } else {
                Log.w(TAG, "Static map error: ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadRouteMap error", e)
        }
    }

    companion object {
        private const val TAG = "TrafficCheckWorker"

        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<TrafficCheckWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }
}

data class RouteResult(val primary: TrafficData, val alt: TrafficData?)

data class TrafficData(
    val duration: Int,
    val durationInTraffic: Int,
    val distance: Int,
    val polyline: String? = null
)
