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
import java.net.URL
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
                
                val apiKey = prefs.getString(TrafficWidgetProvider.KEY_API_KEY, null)
                val homeLat = prefs.getString(TrafficWidgetProvider.KEY_HOME_LAT, null)
                val homeLng = prefs.getString(TrafficWidgetProvider.KEY_HOME_LNG, null)
                
                if (apiKey.isNullOrEmpty() || homeLat.isNullOrEmpty() || homeLng.isNullOrEmpty()) {
                    Log.w(TAG, "Missing configuration")
                    return@withContext Result.success()
                }
                
                // Get current location
                val currentLocation = getCurrentLocation()
                if (currentLocation == null) {
                    Log.w(TAG, "Could not get current location")
                    return@withContext Result.retry()
                }
                
                // Fetch directions with traffic
                val trafficData = fetchTrafficData(
                    apiKey = apiKey,
                    originLat = currentLocation.latitude,
                    originLng = currentLocation.longitude,
                    destLat = homeLat.toDouble(),
                    destLng = homeLng.toDouble()
                )
                
                if (trafficData != null) {
                    // Calculate traffic status
                    val ratio = trafficData.durationInTraffic.toFloat() / trafficData.duration.toFloat()
                    val status = when {
                        ratio < TrafficWidgetProvider.THRESHOLD_GREEN -> TrafficStatus.GREEN
                        ratio < TrafficWidgetProvider.THRESHOLD_YELLOW -> TrafficStatus.YELLOW
                        else -> TrafficStatus.RED
                    }
                    
                    Log.i(TAG, "Traffic ratio: $ratio, status: $status")
                    Log.i(TAG, "Normal: ${trafficData.duration/60}min, With traffic: ${trafficData.durationInTraffic/60}min")
                    
                    // Save to prefs
                    prefs.edit()
                        .putInt(TrafficWidgetProvider.KEY_LAST_TRAFFIC_STATUS, status.ordinal)
                        .putInt(TrafficWidgetProvider.KEY_LAST_DURATION, trafficData.duration)
                        .putInt(TrafficWidgetProvider.KEY_LAST_DURATION_TRAFFIC, trafficData.durationInTraffic)
                        .putLong(TrafficWidgetProvider.KEY_LAST_UPDATE, System.currentTimeMillis())
                        .apply()
                    
                    // Update widget
                    TrafficWidgetProvider.updateAllWidgets(context)
                }
                
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking traffic", e)
                Result.retry()
            }
        }
    }
    
    private fun getCurrentLocation(): Location? {
        return try {
            val fusedLocationClient: FusedLocationProviderClient =
                LocationServices.getFusedLocationProviderClient(context)
            
            // Try to get last known location first
            val lastLocation = Tasks.await(
                fusedLocationClient.lastLocation,
                10, TimeUnit.SECONDS
            )
            
            if (lastLocation != null && 
                System.currentTimeMillis() - lastLocation.time < 10 * 60 * 1000) {
                // Location is recent enough (< 10 minutes)
                return lastLocation
            }
            
            // Request fresh location
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
    
    private suspend fun fetchTrafficData(
        apiKey: String,
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ): TrafficData? = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append("https://maps.googleapis.com/maps/api/directions/json")
                append("?origin=$originLat,$originLng")
                append("&destination=$destLat,$destLng")
                append("&departure_time=now")
                append("&traffic_model=best_guess")
                append("&key=$apiKey")
            }
            
            Log.d(TAG, "Fetching: $url")
            
            val response = URL(url).readText()
            val json = JSONObject(response)
            
            if (json.getString("status") != "OK") {
                Log.w(TAG, "API error: ${json.getString("status")}")
                return@withContext null
            }
            
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) {
                Log.w(TAG, "No routes found")
                return@withContext null
            }
            
            val route = routes.getJSONObject(0)
            val legs = route.getJSONArray("legs")
            val leg = legs.getJSONObject(0)
            
            val duration = leg.getJSONObject("duration").getInt("value")
            val durationInTraffic = if (leg.has("duration_in_traffic")) {
                leg.getJSONObject("duration_in_traffic").getInt("value")
            } else {
                duration // Fall back to normal duration if no traffic data
            }
            
            TrafficData(
                duration = duration,
                durationInTraffic = durationInTraffic,
                distance = leg.getJSONObject("distance").getInt("value")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch traffic data", e)
            null
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
    val duration: Int,           // Normal duration in seconds
    val durationInTraffic: Int,  // Duration with traffic in seconds
    val distance: Int            // Distance in meters
)
