package com.trafficwidget

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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
                val direction = prefs.getInt(TrafficWidgetProvider.KEY_DIRECTION, TrafficWidgetProvider.DIRECTION_TO_HOME)

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

                // GPS mode: override origin with fresh device location
                val useGps = prefs.getBoolean(TrafficWidgetProvider.KEY_USE_GPS, false)
                var finalOriginLat = originLat
                var finalOriginLng = originLng
                if (useGps) {
                    val loc = getLocation()
                    if (loc != null) {
                        finalOriginLat = loc.first.toString()
                        finalOriginLng = loc.second.toString()
                        // Save for future background refreshes
                        prefs.edit()
                            .putString(TrafficWidgetProvider.KEY_GPS_LAT, finalOriginLat)
                            .putString(TrafficWidgetProvider.KEY_GPS_LNG, finalOriginLng)
                            .apply()
                        Log.i(TAG, "GPS origin (fresh): $finalOriginLat, $finalOriginLng")
                    } else {
                        // Background location unavailable — use last saved GPS fix
                        val savedLat = prefs.getString(TrafficWidgetProvider.KEY_GPS_LAT, null)
                        val savedLng = prefs.getString(TrafficWidgetProvider.KEY_GPS_LNG, null)
                        if (!savedLat.isNullOrEmpty() && !savedLng.isNullOrEmpty()) {
                            finalOriginLat = savedLat
                            finalOriginLng = savedLng
                            Log.i(TAG, "GPS origin (cached): $finalOriginLat, $finalOriginLng")
                        }
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

                val oLat = finalOriginLat.toDouble()
                val oLng = finalOriginLng.toDouble()
                val dLat = destLat.toDouble()
                val dLng = destLng.toDouble()

                // No-toll route (primary) + fastest route (alt, may include tolls)
                val noTollResult = fetchTrafficData(apiKey, oLat, oLng, dLat, dLng, avoidTolls = true)
                val fastestResult = fetchTrafficData(apiKey, oLat, oLng, dLat, dLng, avoidTolls = false)

                // Use no-toll as primary; fastest as alt (only if meaningfully different)
                val primary = (noTollResult ?: fastestResult)?.primary ?: run {
                    TrafficWidgetProvider.updateAllWidgets(context)
                    return@withContext Result.success()
                }
                val fastestPrimary = fastestResult?.primary
                // Show fastest as alt only if it's at least 1 min faster than no-toll route
                val altRoute = if (fastestPrimary != null &&
                    fastestPrimary.durationInTraffic < primary.durationInTraffic - 60) {
                    fastestPrimary
                } else null

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

                if (altRoute != null) {
                    editor.putInt(TrafficWidgetProvider.KEY_LAST_ALT_DURATION, altRoute.duration)
                          .putInt(TrafficWidgetProvider.KEY_LAST_ALT_DURATION_TRAFFIC, altRoute.durationInTraffic)
                } else {
                    editor.putInt(TrafficWidgetProvider.KEY_LAST_ALT_DURATION, 0)
                          .putInt(TrafficWidgetProvider.KEY_LAST_ALT_DURATION_TRAFFIC, 0)
                }
                editor.apply()

                // Download map only every 5 minutes — route doesn't change every 30 seconds
                val lastMapDownload = prefs.getLong(KEY_LAST_MAP_DOWNLOAD, 0)
                if (System.currentTimeMillis() - lastMapDownload > MAP_DOWNLOAD_INTERVAL_MS) {
                    downloadRouteMap(
                        apiKey = apiKey,
                        originLat = oLat, originLng = oLng,
                        destLat = dLat, destLng = dLng,
                        primaryPolyline = primary.polyline,
                        altPolyline = altRoute?.polyline
                    )
                    prefs.edit().putLong(KEY_LAST_MAP_DOWNLOAD, System.currentTimeMillis()).apply()
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
            } finally {
                scheduleNextRefresh()
            }
        }
    }

    /** Schedule the next refresh 30 seconds from now (self-perpetuating chain). */
    private fun scheduleNextRefresh() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CHAIN_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TrafficCheckWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )
    }

    /**
     * Get the freshest available device location.
     * Tries last-known first (if < 30s old). Falls back to requesting
     * a single fresh fix with an 8-second timeout.
     */
    @Suppress("MissingPermission")
    private suspend fun getLocation(): Pair<Double, Double>? {
        val hasPerm = context.checkSelfPermission(
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE)
            as android.location.LocationManager

        // Pick the freshest last-known between network and GPS providers
        val network = try { lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { null }
        val gps    = try { lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) }     catch (e: Exception) { null }
        val best = listOfNotNull(network, gps).maxByOrNull { it.time }

        if (best != null && System.currentTimeMillis() - best.time < 30_000L) {
            return Pair(best.latitude, best.longitude)   // fresh enough
        }

        // Request a single fresh fix (timeout 8 s)
        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(loc: android.location.Location) {
                        try { lm.removeUpdates(this) } catch (e: Exception) {}
                        if (cont.isActive) cont.resume(Pair(loc.latitude, loc.longitude), null)
                    }
                    override fun onProviderDisabled(p: String) {}
                }
                try {
                    lm.requestLocationUpdates(
                        android.location.LocationManager.NETWORK_PROVIDER,
                        0L, 0f, listener, android.os.Looper.getMainLooper()
                    )
                    cont.invokeOnCancellation { try { lm.removeUpdates(listener) } catch (e: Exception) {} }
                } catch (e: Exception) {
                    Log.e(TAG, "requestLocationUpdates failed", e)
                    // Fall back to best stale fix
                    if (cont.isActive) cont.resume(best?.let { Pair(it.latitude, it.longitude) }, null)
                }
            }
        } ?: best?.let { Pair(it.latitude, it.longitude) }  // timeout → use stale if available
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
        destLat: Double, destLng: Double,
        avoidTolls: Boolean = false
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
                put("computeAlternativeRoutes", false)
                put("languageCode", "en-US")
                put("units", "METRIC")
                if (avoidTolls) {
                    put("routeModifiers", JSONObject().apply {
                        put("avoidTolls", true)
                    })
                }
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
            sb.append("size=480x300")
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
        const val CHAIN_WORK_NAME = "traffic_auto_chain"
        const val IMMEDIATE_WORK_NAME = "traffic_immediate"

        fun enqueueNow(context: Context) {
            // REPLACE prevents pileup: if alarm fires every 30s but API takes 15s,
            // we don't want a queue of pending workers stacking up.
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TrafficCheckWorker>()
                    .build()
            )
        }

        fun cancelChain(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(CHAIN_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
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
