package com.trafficwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import androidx.work.*
import java.util.concurrent.TimeUnit

class TrafficWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
        
        // Schedule periodic updates
        scheduleTrafficCheck(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleTrafficCheck(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        when (intent.action) {
            ACTION_REFRESH -> {
                // Manual refresh triggered
                TrafficCheckWorker.enqueueNow(context)
            }
            ACTION_OPEN_WAZE -> {
                // Open Waze with navigation to home
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val homeLat = prefs.getString(KEY_HOME_LAT, null)
                val homeLng = prefs.getString(KEY_HOME_LNG, null)
                
                if (homeLat != null && homeLng != null) {
                    val wazeIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("waze://?ll=$homeLat,$homeLng&navigate=yes")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(wazeIntent)
                    } catch (e: Exception) {
                        // Waze not installed, open Google Maps
                        val mapsIntent = Intent(Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse("google.navigation:q=$homeLat,$homeLng")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(mapsIntent)
                    }
                }
            }
        }
    }

    private fun scheduleTrafficCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<TrafficCheckWorker>(
            15, TimeUnit.MINUTES  // Minimum interval for periodic work
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    companion object {
        const val PREFS_NAME = "traffic_widget_prefs"
        const val KEY_HOME_LAT = "home_lat"
        const val KEY_HOME_LNG = "home_lng"
        const val KEY_HOME_ADDRESS = "home_address"
        const val KEY_WORK_LAT = "work_lat"
        const val KEY_WORK_LNG = "work_lng"
        const val KEY_WORK_ADDRESS = "work_address"
        const val KEY_API_KEY = "google_api_key"
        const val KEY_LAST_TRAFFIC_STATUS = "last_traffic_status"
        const val KEY_LAST_DURATION = "last_duration"
        const val KEY_LAST_DURATION_TRAFFIC = "last_duration_traffic"
        const val KEY_LAST_UPDATE = "last_update"
        
        const val ACTION_REFRESH = "com.trafficwidget.ACTION_REFRESH"
        const val ACTION_OPEN_WAZE = "com.trafficwidget.ACTION_OPEN_WAZE"
        const val WORK_NAME = "traffic_check_work"

        // Traffic status thresholds (ratio of traffic time to normal time)
        const val THRESHOLD_GREEN = 1.15f   // < 15% slower = green
        const val THRESHOLD_YELLOW = 1.35f  // < 35% slower = yellow, else red

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val trafficStatus = prefs.getInt(KEY_LAST_TRAFFIC_STATUS, TrafficStatus.UNKNOWN.ordinal)
            val duration = prefs.getInt(KEY_LAST_DURATION, 0)
            val durationTraffic = prefs.getInt(KEY_LAST_DURATION_TRAFFIC, 0)
            val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0)

            val views = RemoteViews(context.packageName, R.layout.widget_traffic)
            
            // Set gauge color based on traffic status
            val status = TrafficStatus.values()[trafficStatus]
            val (color, emoji, label) = when (status) {
                TrafficStatus.GREEN -> Triple(Color.parseColor("#4CAF50"), "🟢", "Clear")
                TrafficStatus.YELLOW -> Triple(Color.parseColor("#FFC107"), "🟡", "Moderate")
                TrafficStatus.RED -> Triple(Color.parseColor("#F44336"), "🔴", "Heavy")
                TrafficStatus.UNKNOWN -> Triple(Color.parseColor("#9E9E9E"), "⚪", "Unknown")
            }
            
            views.setInt(R.id.gaugeIndicator, "setBackgroundColor", color)
            views.setTextViewText(R.id.statusEmoji, emoji)
            views.setTextViewText(R.id.statusLabel, label)
            
            // Show travel time info
            if (durationTraffic > 0) {
                val minutes = durationTraffic / 60
                views.setTextViewText(R.id.travelTime, "${minutes} min")
                
                // Show delay if any
                val delay = (durationTraffic - duration) / 60
                if (delay > 0) {
                    views.setTextViewText(R.id.delayInfo, "+${delay} min delay")
                } else {
                    views.setTextViewText(R.id.delayInfo, "No delay")
                }
            } else {
                views.setTextViewText(R.id.travelTime, "-- min")
                views.setTextViewText(R.id.delayInfo, "Tap to configure")
            }
            
            // Last update time
            if (lastUpdate > 0) {
                val ago = (System.currentTimeMillis() - lastUpdate) / 60000
                views.setTextViewText(R.id.lastUpdate, "Updated ${ago}m ago")
            }
            
            // Set click handlers
            val refreshIntent = Intent(context, TrafficWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPending = PendingIntent.getBroadcast(
                context, 0, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.refreshButton, refreshPending)
            
            // Click on gauge opens Waze/navigation
            val wazeIntent = Intent(context, TrafficWidgetProvider::class.java).apply {
                action = ACTION_OPEN_WAZE
            }
            val wazePending = PendingIntent.getBroadcast(
                context, 1, wazeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.gaugeContainer, wazePending)
            
            // Click on settings opens config
            val configIntent = Intent(context, ConfigActivity::class.java)
            val configPending = PendingIntent.getActivity(
                context, 2, configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.settingsButton, configPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, TrafficWidgetProvider::class.java)
            )
            for (widgetId in widgetIds) {
                updateWidget(context, appWidgetManager, widgetId)
            }
        }
    }
}

enum class TrafficStatus {
    UNKNOWN, GREEN, YELLOW, RED
}
