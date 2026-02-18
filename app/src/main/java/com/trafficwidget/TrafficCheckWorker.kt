package com.trafficwidget

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

                // Geocode home address if coordinates missing
                if ((homeLat.isNullOrEmpty() || homeLng.isNullOrEmpty()) && apiKey != null) {
                    val addr = prefs.getString(TrafficWidgetProvider.KEY_HOME_ADDRESS, TrafficWidgetProvider.DEFAULT_HOME_ADDRESS)
                    if (!addr.isNullOrEmpty()) {
                        Log.i(TAG, "Geocoding home: $addr")
                        val coords = geocodeAddress(addr, apiKey)
                        if (coords != null) {
                            homeLat = coords.first.toString()
                            homeLng = coords.second.toString()
                            prefs.edit()
                                .putString(TrafficWidgetProvider.KEY_HOME_LAT, homeLat)
                                .putString(TrafficWidgetProvider.KEY_HOME_LNG, homeLng)
                                .apply()
                        }
                    }
                }

                // Geocode work address if coordinates missing
                if ((workLat.isNullOrEmpty() || workLng.isNullOrEmpty()) && apiKey != null) {
                    val addr = prefs.getString(TrafficWidgetProvider.KEY_WORK_ADDRESS, TrafficWidgetProvider.DEFAULT_WORK_ADDRESS)
                    if (!addr.isNullOrEmpty()) {
                        Log.i(TAG, "Geocoding work: $addr")
                        val coords = geocodeAddress(addr, apiKey)
                        if (coords != null) {
                            workLat = coords.first.toString()
                            workLng = coords.second.toString()
                            prefs.edit()
                                .putString(TrafficWidgetProvider.KEY_WORK_LAT, workLat)
                                .putString(TrafficWidgetProvider.KEY_WORK_LNG, workLng)
                                .apply()
                        }
                    }
                }

                // Fixed routes — no GPS needed:
                // TO_WORK = Home → Work
                // TO_HOME = Work → Home
                val originLat: String?
                val originLng: String?
                val destLat: String?
                val destLng: String?

                if (direction == TrafficWidgetProvider.DIRECTION_TO_WORK) {
                    originLat = homeLat; originLng = homeLng
                    destLat = workLat;  destLng = workLng
                } else {
                    originLat = workLat; originLng = workLng
                    destLat = homeLat;  destLng = homeLng
                }

                if (apiKey.isNullOrEmpty() || originLat.isNullOrEmpty() || originLng.isNullOrEmpty()
                    || destLat.isNullOrEmpty() || destLng.isNullOrEmpty()) {
                    val msg = when {
                        apiKey.isNullOrEmpty() -> "API key not configured"
                        homeLat.isNullOrEmpty() -> "Home address not configured"
                        workLat.isNullOrEmpty() -> "Work address not configured"
                        else -> "Addresses not configured"
                    }
                    Log.w(TAG, msg)
                    prefs.edit()
                        .putString(TrafficWidgetProvider.KEY_LAST_ERROR, msg)
                        .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                        .apply()
                    TrafficWidgetProvider.updateAllWidgets(context)
                    return@withContext Result.success()
                }

                val trafficData = fetchTrafficData(
                    apiKey = apiKey,
                    originLat = originLat.toDouble(),
                    originLng = originLng.toDouble(),
                    destLat = destLat.toDouble(),
                    destLng = destLng.toDouble()
                )

                if (trafficData != null) {
                    val ratio = trafficData.durationInTraffic.toFloat() / trafficData.duration.toFloat()
                    val status = when {
                        ratio < TrafficWidgetProvider.THRESHOLD_GREEN -> TrafficStatus.GREEN
                        ratio < TrafficWidgetProvider.THRESHOLD_YELLOW -> TrafficStatus.YELLOW
                        else -> TrafficStatus.RED
                    }
                    Log.i(TAG, "Ratio: $ratio, status: $status, normal: ${trafficData.duration/60}min, traffic: ${trafficData.durationInTraffic/60}min")

                    prefs.edit()
                        .putInt(TrafficWidgetProvider.KEY_LAST_TRAFFIC_STATUS, status.ordinal)
                        .putInt(TrafficWidgetProvider.KEY_LAST_DURATION, trafficData.duration)
                        .putInt(TrafficWidgetProvider.KEY_LAST_DURATION_TRAFFIC, trafficData.durationInTraffic)
                        .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                        .remove(TrafficWidgetProvider.KEY_LAST_ERROR)
                        .apply()
                }

                TrafficWidgetProvider.updateAllWidgets(context)
                Result.success()

            } catch (e: Exception) {
                Log.e(TAG, "Error checking traffic", e)
                val prefs = context.getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                val msg = when (e) {
                    is java.net.UnknownHostException -> "No internet connection"
                    is java.net.SocketTimeoutException -> "Request timeout"
                    else -> "Error: ${e.message ?: "Unknown"}"
                }
                prefs.edit()
                    .putString(TrafficWidgetProvider.KEY_LAST_ERROR, msg)
                    .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply()
                TrafficWidgetProvider.updateAllWidgets(context)
                Result.retry()
            }
        }
    }

    private suspend fun geocodeAddress(address: String, apiKey: String): Pair<Double, Double>? {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(address, "UTF-8")
                val url = URL("https://maps.googleapis.com/maps/api/geocode/json?address=$encoded&key=$apiKey")
                val conn = url.openConnection()
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                val json = JSONObject(conn.getInputStream().bufferedReader().readText())
                if (json.getString("status") != "OK") return@withContext null
                val loc = json.getJSONArray("results").getJSONObject(0)
                    .getJSONObject("geometry").getJSONObject("location")
                Pair(loc.getDouble("lat"), loc.getDouble("lng"))
            } catch (e: Exception) {
                Log.e(TAG, "Geocode error", e)
                null
            }
        }
    }

    private suspend fun fetchTrafficData(
        apiKey: String,
        originLat: Double, originLng: Double,
        destLat: Double, destLng: Double
    ): TrafficData? = withContext(Dispatchers.IO) {
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
            }

            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Goog-Api-Key", apiKey)
            conn.setRequestProperty("X-Goog-FieldMask", "routes.duration,routes.staticDuration,routes.distanceMeters")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()); it.flush() }

            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "Routes API error ${conn.responseCode}: $err")
                val prefs = context.getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                val msg = try { JSONObject(err ?: "{}").optJSONObject("error")?.optString("message", "API error") } catch (e: Exception) { "API error" }
                prefs.edit()
                    .putString(TrafficWidgetProvider.KEY_LAST_ERROR, "API: $msg")
                    .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply()
                return@withContext null
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val routes = json.optJSONArray("routes") ?: return@withContext null
            if (routes.length() == 0) return@withContext null

            val route = routes.getJSONObject(0)
            val durationInTraffic = route.optString("duration", "0s").removeSuffix("s").toIntOrNull() ?: 0
            val duration = route.optString("staticDuration", "0s").removeSuffix("s").toIntOrNull() ?: durationInTraffic

            TrafficData(
                duration = if (duration > 0) duration else durationInTraffic,
                durationInTraffic = durationInTraffic,
                distance = route.optInt("distanceMeters", 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchTrafficData error", e)
            null
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

data class TrafficData(
    val duration: Int,
    val durationInTraffic: Int,
    val distance: Int
)
