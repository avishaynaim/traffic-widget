package com.trafficwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

class TrafficWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
        scheduleTrafficCheck(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        initializeDefaults(context)
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
                TrafficCheckWorker.enqueueNow(context)
            }
            ACTION_OPEN_WAZE -> {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val direction = prefs.getInt(KEY_DIRECTION, DIRECTION_TO_HOME)

                val lat: String?
                val lng: String?
                if (direction == DIRECTION_TO_WORK) {
                    lat = prefs.getString(KEY_WORK_LAT, null)
                    lng = prefs.getString(KEY_WORK_LNG, null)
                } else {
                    lat = prefs.getString(KEY_HOME_LAT, null)
                    lng = prefs.getString(KEY_HOME_LNG, null)
                }

                if (lat != null && lng != null) {
                    val wazeIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("waze://?ll=$lat,$lng&navigate=yes")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(wazeIntent)
                    } catch (e: Exception) {
                        val mapsIntent = Intent(Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse("google.navigation:q=$lat,$lng")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(mapsIntent)
                    }
                }
            }
            ACTION_TOGGLE_DIRECTION -> {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val current = prefs.getInt(KEY_DIRECTION, DIRECTION_TO_HOME)
                val next = if (current == DIRECTION_TO_HOME) DIRECTION_TO_WORK else DIRECTION_TO_HOME
                prefs.edit().putInt(KEY_DIRECTION, next).apply()
                // Refresh traffic data for new direction, then update display
                TrafficCheckWorker.enqueueNow(context)
                updateAllWidgets(context)
            }
        }
    }

    private fun scheduleTrafficCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<TrafficCheckWorker>(
            15, TimeUnit.MINUTES
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
        const val DEFAULT_API_KEY = "AIzaSyALvbBxcZQpHExPUiL4uUvfU4JfbxqjANI"
        const val DEFAULT_HOME_ADDRESS = "Bar Kochva, Rehovot"
        const val DEFAULT_WORK_ADDRESS = "Dizengof, Beer Yaakov"
        const val KEY_LAST_TRAFFIC_STATUS = "last_traffic_status"
        const val KEY_LAST_DURATION = "last_duration"
        const val KEY_LAST_DURATION_TRAFFIC = "last_duration_traffic"
        const val KEY_LAST_UPDATE = "last_update"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_DIRECTION = "direction"

        const val DIRECTION_TO_HOME = 0
        const val DIRECTION_TO_WORK = 1

        const val ACTION_REFRESH = "com.trafficwidget.ACTION_REFRESH"
        const val ACTION_OPEN_WAZE = "com.trafficwidget.ACTION_OPEN_WAZE"
        const val ACTION_TOGGLE_DIRECTION = "com.trafficwidget.ACTION_TOGGLE_DIRECTION"
        const val WORK_NAME = "traffic_check_work"

        // Traffic status thresholds (ratio of traffic time to normal time)
        const val THRESHOLD_GREEN = 1.15f   // < 15% slower = green
        const val THRESHOLD_YELLOW = 1.35f  // < 35% slower = yellow, else red

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val trafficStatus = prefs.getInt(KEY_LAST_TRAFFIC_STATUS, TrafficStatus.UNKNOWN.ordinal)
                val duration = prefs.getInt(KEY_LAST_DURATION, 0)
                val durationTraffic = prefs.getInt(KEY_LAST_DURATION_TRAFFIC, 0)
                val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0)
                val lastError = prefs.getString(KEY_LAST_ERROR, null)
                val direction = prefs.getInt(KEY_DIRECTION, DIRECTION_TO_HOME)

                val views = RemoteViews(context.packageName, R.layout.widget_traffic)

                // Direction button label
                val dirLabel = if (direction == DIRECTION_TO_WORK) "→🏢 To Work" else "→🏠 To Home"
                views.setTextViewText(R.id.directionButton, dirLabel)

                // Status label
                val status = TrafficStatus.values()[trafficStatus]
                val label = when (status) {
                    TrafficStatus.GREEN -> "Clear"
                    TrafficStatus.YELLOW -> "Moderate"
                    TrafficStatus.RED -> "Heavy"
                    TrafficStatus.UNKNOWN -> "Unknown"
                }
                views.setTextViewText(R.id.statusLabel, label)

                // Gauge bitmap: ratio of traffic time to normal time
                val ratio = if (duration > 0 && durationTraffic > 0)
                    durationTraffic.toFloat() / duration.toFloat()
                else
                    1.0f
                val gaugeBitmap = createGaugeBitmap(ratio)
                views.setImageViewBitmap(R.id.gaugeImage, gaugeBitmap)

                // Travel time or error
                if (!lastError.isNullOrEmpty()) {
                    views.setTextViewText(R.id.travelTime, "Error")
                    views.setTextViewText(R.id.delayInfo, lastError)
                } else if (durationTraffic > 0) {
                    val minutes = durationTraffic / 60
                    views.setTextViewText(R.id.travelTime, "${minutes} min")
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

                // Click: refresh button
                val refreshIntent = Intent(context, TrafficWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH
                }
                val refreshPending = PendingIntent.getBroadcast(
                    context, 0, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.refreshButton, refreshPending)

                // Click: gauge area opens navigation
                val wazeIntent = Intent(context, TrafficWidgetProvider::class.java).apply {
                    action = ACTION_OPEN_WAZE
                }
                val wazePending = PendingIntent.getBroadcast(
                    context, 1, wazeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.gaugeContainer, wazePending)

                // Click: settings button
                val configIntent = Intent(context, ConfigActivity::class.java)
                val configPending = PendingIntent.getActivity(
                    context, 2, configIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.settingsButton, configPending)

                // Click: direction toggle button
                val toggleIntent = Intent(context, TrafficWidgetProvider::class.java).apply {
                    action = ACTION_TOGGLE_DIRECTION
                }
                val togglePending = PendingIntent.getBroadcast(
                    context, 3, toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.directionButton, togglePending)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                android.util.Log.e("TrafficWidget", "Error updating widget", e)
                try {
                    val errorViews = RemoteViews(context.packageName, R.layout.widget_traffic)
                    errorViews.setTextViewText(R.id.statusLabel, "Error")
                    errorViews.setTextViewText(R.id.travelTime, "!")
                    errorViews.setTextViewText(R.id.delayInfo, "Tap settings to configure")
                    appWidgetManager.updateAppWidget(appWidgetId, errorViews)
                } catch (e2: Exception) {
                    android.util.Log.e("TrafficWidget", "Failed to show error view", e2)
                }
            }
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

        /**
         * Draws a semicircular speedometer gauge bitmap.
         * Arc: LEFT=RED (heavy traffic) → TOP=YELLOW → RIGHT=GREEN (clear roads).
         * Needle points based on traffic ratio (1.0=green/right, 2.0+=red/left).
         */
        fun createGaugeBitmap(ratio: Float): Bitmap {
            val W = 200
            val H = 110
            val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val cx = W / 2f
            val cy = H.toFloat()   // center at bottom of bitmap
            val radius = H * 0.88f
            val strokeW = 14f

            val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            // Background track (semi-transparent white arc)
            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.ROUND
                color = Color.parseColor("#44FFFFFF")
            }
            canvas.drawArc(oval, 180f, 180f, false, trackPaint)

            // Gradient arc: RED (left/180°) → YELLOW (top/270°) → GREEN (right/360°)
            // Draw in segments for smooth color transition
            val segments = 60
            val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.BUTT
            }
            val segSweep = 180f / segments + 0.5f  // slight overlap prevents gaps
            for (i in 0 until segments) {
                val t = i.toFloat() / (segments - 1)  // 0.0=left/RED, 1.0=right/GREEN
                arcPaint.color = when {
                    t < 0.5f -> interpolateColor(
                        Color.parseColor("#F44336"),   // Red
                        Color.parseColor("#FFC107"),   // Yellow
                        t * 2f
                    )
                    else -> interpolateColor(
                        Color.parseColor("#FFC107"),   // Yellow
                        Color.parseColor("#4CAF50"),   // Green
                        (t - 0.5f) * 2f
                    )
                }
                val startAngle = 180f + t * 180f
                canvas.drawArc(oval, startAngle, segSweep, false, arcPaint)
            }

            // Needle: fraction=0 → right (GREEN, 0°), fraction=1 → left (RED, 180°)
            val maxRatio = 2.0f
            val fraction = ((ratio - 1f) / (maxRatio - 1f)).coerceIn(0f, 1f)
            val needleAngleRad = Math.toRadians((fraction * 180.0))
            val needleLen = radius - 12f
            val nx = (cx + needleLen * cos(needleAngleRad)).toFloat()
            val ny = (cy - needleLen * sin(needleAngleRad)).toFloat()

            val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = 4f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(cx, cy, nx, ny, needlePaint)

            // Center pivot dot
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
            }
            canvas.drawCircle(cx, cy, 7f, dotPaint)

            return bitmap
        }

        private fun interpolateColor(a: Int, b: Int, t: Float): Int {
            val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
            val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
            val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
            return Color.rgb(r, g, bl)
        }

        private fun initializeDefaults(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val homeLat = prefs.getString(KEY_HOME_LAT, null)
            if (homeLat != null) return

            prefs.edit()
                .putString(KEY_API_KEY, DEFAULT_API_KEY)
                .putString(KEY_HOME_ADDRESS, DEFAULT_HOME_ADDRESS)
                .putString(KEY_WORK_ADDRESS, DEFAULT_WORK_ADDRESS)
                .apply()

            android.util.Log.i("TrafficWidget", "Initialized with default values")
        }
    }
}


enum class TrafficStatus {
    UNKNOWN, GREEN, YELLOW, RED
}
