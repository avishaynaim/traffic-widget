package com.trafficwidget

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.work.*
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
                    TrafficWidgetProvider.PREFS_NAME,
                    Context.MODE_PRIVATE
                )

                val apiKey = prefs.getString(TrafficWidgetProvider.KEY_API_KEY, TrafficWidgetProvider.DEFAULT_API_KEY)
                val direction = prefs.getInt(TrafficWidgetProvider.KEY_DIRECTION, TrafficWidgetProvider.DIRECTION_TO_HOME)

                var homeLat = prefs.getString(TrafficWidgetProvider.KEY_HOME_LAT, null)
                var homeLng = prefs.getString(TrafficWidgetProvider.KEY_HOME_LNG, null)
                var workLat = prefs.getString(TrafficWidgetProvider.KEY_WORK_LAT, null)
                var workLng = prefs.getString(TrafficWidgetProvider.KEY_WORK_LNG, null)

                // Auto-geocode home address if coordinates missing
                if ((homeLat.isNullOrEmpty() || homeLng.isNullOrEmpty()) && apiKey != null) {
                    val homeAddress = prefs.getString(TrafficWidgetProvider.KEY_HOME_ADDRESS, TrafficWidgetProvider.DEFAULT_HOME_ADDRESS)
                    if (!homeAddress.isNullOrEmpty()) {
                        Log.i(TAG, "Auto-geocoding home address: $homeAddress")
                        val coords = geocodeAddress(homeAddress, apiKey)
                        if (coords != null) {
                            homeLat = coords.first.toString()
                            homeLng = coords.second.toString()
                            prefs.edit()
                                .putString(TrafficWidgetProvider.KEY_HOME_LAT, homeLat)
                                .putString(TrafficWidgetProvider.KEY_HOME_LNG, homeLng)
                                .apply()
                            Log.i(TAG, "Home geocoded: $homeLat, $homeLng")
                        }
                    }
                }

                // Auto-geocode work address if direction=TO_WORK and coordinates missing
                if (direction == TrafficWidgetProvider.DIRECTION_TO_WORK &&
                    (workLat.isNullOrEmpty() || workLng.isNullOrEmpty()) && apiKey != null) {
                    val workAddress = prefs.getString(TrafficWidgetProvider.KEY_WORK_ADDRESS, TrafficWidgetProvider.DEFAULT_WORK_ADDRESS)
                    if (!workAddress.isNullOrEmpty()) {
                        Log.i(TAG, "Auto-geocoding work address: $workAddress")
                        val coords = geocodeAddress(workAddress, apiKey)
                        if (coords != null) {
                            workLat = coords.first.toString()
                            workLng = coords.second.toString()
                            prefs.edit()
                                .putString(TrafficWidgetProvider.KEY_WORK_LAT, workLat)
                                .putString(TrafficWidgetProvider.KEY_WORK_LNG, workLng)
                                .apply()
                            Log.i(TAG, "Work geocoded: $workLat, $workLng")
                        }
                    }
                }

                // Determine destination based on direction
                val destLat: String?
                val destLng: String?
                if (direction == TrafficWidgetProvider.DIRECTION_TO_WORK) {
                    destLat = workLat
                    destLng = workLng
                } else {
                    destLat = homeLat
                    destLng = homeLng
                }

                if (apiKey.isNullOrEmpty() || destLat.isNullOrEmpty() || destLng.isNullOrEmpty()) {
                    val msg = when {
                        apiKey.isNullOrEmpty() -> "API key not configured"
                        direction == TrafficWidgetProvider.DIRECTION_TO_WORK -> "Work address not configured"
                        else -> "Home address not configured"
                    }
                    Log.w(TAG, "Missing configuration: $msg")
                    prefs.edit()
                        .putString(TrafficWidgetProvider.KEY_LAST_ERROR, msg)
                        .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                        .apply()
                    TrafficWidgetProvider.updateAllWidgets(context)
                    return@withContext Result.success()
                }

                // Get current location
                val currentLocation = getCurrentLocation()
                if (currentLocation == null) {
                    Log.w(TAG, "Could not get current location")
                    prefs.edit()
                        .putString(TrafficWidgetProvider.KEY_LAST_ERROR, "Cannot get location. Check permissions.")
                        .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                        .apply()
                    TrafficWidgetProvider.updateAllWidgets(context)
                    return@withContext Result.retry()
                }

                // Fetch traffic data
                val trafficData = fetchTrafficData(
                    apiKey = apiKey,
                    originLat = currentLocation.latitude,
                    originLng = currentLocation.longitude,
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

                    Log.i(TAG, "Traffic ratio: $ratio, status: $status")
                    Log.i(TAG, "Normal: ${trafficData.duration/60}min, With traffic: ${trafficData.durationInTraffic/60}min")

                    prefs.edit()
                        .putInt(TrafficWidgetProvider.KEY_LAST_TRAFFIC_STATUS, status.ordinal)
                        .putInt(TrafficWidgetProvider.KEY_LAST_DURATION, trafficData.duration)
                        .putInt(TrafficWidgetProvider.KEY_LAST_DURATION_TRAFFIC, trafficData.durationInTraffic)
                        .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                        .remove(TrafficWidgetProvider.KEY_LAST_ERROR)
                        .apply()

                    TrafficWidgetProvider.updateAllWidgets(context)
                } else {
                    TrafficWidgetProvider.updateAllWidgets(context)
                }

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking traffic", e)

                val prefs = context.getSharedPreferences(
                    TrafficWidgetProvider.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                val errorMsg = when (e) {
                    is java.net.UnknownHostException -> "No internet connection"
                    is java.net.SocketTimeoutException -> "Request timeout"
                    is SecurityException -> "Location permission denied"
                    else -> "Error: ${e.message ?: "Unknown error"}"
                }
                prefs.edit()
                    .putString(TrafficWidgetProvider.KEY_LAST_ERROR, errorMsg)
                    .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply()
                TrafficWidgetProvider.updateAllWidgets(context)

                Result.retry()
            }
        }
    }

    private fun getCurrentLocation(): Location? {
        return try {
            val fusedLocationClient: FusedLocationProviderClient =
                LocationServices.getFusedLocationProviderClient(context)

            val lastLocation = Tasks.await(
                fusedLocationClient.lastLocation,
                10, TimeUnit.SECONDS
            )

            if (lastLocation != null &&
                System.currentTimeMillis() - lastLocation.time < 10 * 60 * 1000) {
                return lastLocation
            }

            val locationTask = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                null
            )
            Tasks.await(locationTask, 30, TimeUnit.SECONDS)
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get location", e)
            null
        }
    }

    /**
     * Geocode an address to lat/lng using Google Geocoding API
     */
    private suspend fun geocodeAddress(address: String, apiKey: String): Pair<Double, Double>? {
        return withContext(Dispatchers.IO) {
            try {
                val encodedAddress = URLEncoder.encode(address, "UTF-8")
                val url = URL("https://maps.googleapis.com/maps/api/geocode/json?address=$encodedAddress&key=$apiKey")
                val connection = url.openConnection()
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                val response = connection.getInputStream().bufferedReader().readText()
                val json = JSONObject(response)

                val status = json.getString("status")
                if (status != "OK") {
                    Log.w(TAG, "Geocoding failed with status: $status")
                    return@withContext null
                }

                val results = json.getJSONArray("results")
                if (results.length() == 0) return@withContext null

                val location = results.getJSONObject(0)
                    .getJSONObject("geometry")
                    .getJSONObject("location")

                Pair(location.getDouble("lat"), location.getDouble("lng"))
            } catch (e: Exception) {
                Log.e(TAG, "Geocoding error", e)
                null
            }
        }
    }

    /**
     * Fetches traffic data using the Google Routes API v2
     * Endpoint: https://routes.googleapis.com/directions/v2:computeRoutes
     */
    private suspend fun fetchTrafficData(
        apiKey: String,
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ): TrafficData? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://routes.googleapis.com/directions/v2:computeRoutes")

            val requestBody = JSONObject().apply {
                put("origin", JSONObject().apply {
                    put("location", JSONObject().apply {
                        put("latLng", JSONObject().apply {
                            put("latitude", originLat)
                            put("longitude", originLng)
                        })
                    })
                })
                put("destination", JSONObject().apply {
                    put("location", JSONObject().apply {
                        put("latLng", JSONObject().apply {
                            put("latitude", destLat)
                            put("longitude", destLng)
                        })
                    })
                })
                put("travelMode", "DRIVE")
                put("routingPreference", "TRAFFIC_AWARE")
                put("computeAlternativeRoutes", false)
                put("routeModifiers", JSONObject().apply {
                    put("avoidTolls", false)
                    put("avoidHighways", false)
                    put("avoidFerries", false)
                })
                put("languageCode", "en-US")
                put("units", "METRIC")
            }

            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Goog-Api-Key", apiKey)
            connection.setRequestProperty("X-Goog-FieldMask", "routes.duration,routes.staticDuration,routes.distanceMeters")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "Routes API error $responseCode: $errorStream")

                try {
                    val errorJson = JSONObject(errorStream ?: "{}")
                    val errorMessage = errorJson.optJSONObject("error")?.optString("message", "Unknown error")
                    val prefs = context.getSharedPreferences(
                        TrafficWidgetProvider.PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                    prefs.edit()
                        .putString(TrafficWidgetProvider.KEY_LAST_ERROR, "API Error: $errorMessage")
                        .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                        .apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse error", e)
                }

                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().readText()
            Log.d(TAG, "Routes API response: $response")

            val json = JSONObject(response)
            val routes = json.optJSONArray("routes")

            if (routes == null || routes.length() == 0) {
                Log.w(TAG, "No routes found")
                val prefs = context.getSharedPreferences(
                    TrafficWidgetProvider.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putString(TrafficWidgetProvider.KEY_LAST_ERROR, "No route found to destination")
                    .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply()
                return@withContext null
            }

            val route = routes.getJSONObject(0)

            // duration = with traffic, staticDuration = without traffic
            val durationStr = route.optString("duration", "0s")
            val durationInTraffic = parseDuration(durationStr)

            val staticDurationStr = route.optString("staticDuration", durationStr)
            val duration = parseDuration(staticDurationStr)

            val distance = route.optInt("distanceMeters", 0)

            Log.d(TAG, "Parsed: duration=$duration, durationInTraffic=$durationInTraffic, distance=$distance")

            TrafficData(
                duration = if (duration > 0) duration else durationInTraffic,
                durationInTraffic = durationInTraffic,
                distance = distance
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch traffic data", e)
            null
        }
    }

    private fun parseDuration(durationStr: String): Int {
        return try {
            durationStr.removeSuffix("s").toInt()
        } catch (e: Exception) {
            0
        }
    }

    companion object {
        private const val TAG = "TrafficCheckWorker"

        fun enqueueNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<TrafficCheckWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}

data class TrafficData(
    val duration: Int,           // Normal duration in seconds (staticDuration)
    val durationInTraffic: Int,  // Duration with traffic in seconds
    val distance: Int            // Distance in meters
)
