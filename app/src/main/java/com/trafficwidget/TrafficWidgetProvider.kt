package com.trafficwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        TrafficCheckWorker.enqueueNow(context)  // fetch fresh ETA, map, and routes on every system update
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        initializeDefaults(context)
        scheduleTrafficCheck(context)
        TrafficCheckWorker.enqueueNow(context)  // starts the 30s chain
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        TrafficCheckWorker.cancelChain(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH -> {
                TrafficCheckWorker.enqueueNow(context)
            }
            ACTION_TOGGLE_GPS -> {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val current = prefs.getBoolean(KEY_USE_GPS, false)
                if (!current) {
                    // Turning GPS on — check permission
                    val hasPerm = context.checkSelfPermission(
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!hasPerm) {
                        prefs.edit().putString(KEY_LAST_ERROR, "Grant location permission in Settings").apply()
                        updateAllWidgets(context)
                        return
                    }
                }
                prefs.edit().putBoolean(KEY_USE_GPS, !current).apply()
                TrafficCheckWorker.enqueueNow(context)
                updateAllWidgets(context)
            }
            ACTION_TOGGLE_ROUTE -> {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val current = prefs.getBoolean(KEY_SHOW_ALT, false)
                prefs.edit().putBoolean(KEY_SHOW_ALT, !current).apply()
                updateAllWidgets(context)
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
        const val KEY_LAST_ALT_DURATION = "last_alt_duration"
        const val KEY_LAST_ALT_DURATION_TRAFFIC = "last_alt_duration_traffic"
        const val KEY_LAST_UPDATE = "last_update"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_DIRECTION = "direction"
        const val MAP_FILE_NAME = "route_map.png"

        const val DIRECTION_TO_HOME = 0
        const val DIRECTION_TO_WORK = 1

        const val ACTION_REFRESH = "com.trafficwidget.ACTION_REFRESH"
        const val ACTION_OPEN_WAZE = "com.trafficwidget.ACTION_OPEN_WAZE"
        const val ACTION_TOGGLE_DIRECTION = "com.trafficwidget.ACTION_TOGGLE_DIRECTION"
        const val ACTION_TOGGLE_GPS = "com.trafficwidget.ACTION_TOGGLE_GPS"
        const val ACTION_TOGGLE_ROUTE = "com.trafficwidget.ACTION_TOGGLE_ROUTE"
        const val WORK_NAME = "traffic_check_work"

        const val KEY_USE_GPS = "use_gps"
        const val KEY_SHOW_ALT = "show_alt"
        const val KEY_GPS_LAT = "gps_lat"
        const val KEY_GPS_LNG = "gps_lng"

        // Traffic status thresholds (ratio of traffic time to normal time)
        const val THRESHOLD_GREEN = 1.15f   // < 15% slower = green
        const val THRESHOLD_YELLOW = 1.35f  // < 35% slower = yellow, else red

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_traffic)

            // Always set click handlers first — before any code that could throw
            val refreshIntent = Intent(context, TrafficWidgetProvider::class.java).apply { action = ACTION_REFRESH }
            views.setOnClickPendingIntent(R.id.refreshButton, PendingIntent.getBroadcast(
                context, 0, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val wazeIntent = Intent(context, TrafficWidgetProvider::class.java).apply { action = ACTION_OPEN_WAZE }
            views.setOnClickPendingIntent(R.id.gaugeContainer, PendingIntent.getBroadcast(
                context, 1, wazeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val configIntent = Intent(context, ConfigActivity::class.java)
            views.setOnClickPendingIntent(R.id.settingsButton, PendingIntent.getActivity(
                context, 2, configIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val toggleIntent = Intent(context, TrafficWidgetProvider::class.java).apply { action = ACTION_TOGGLE_DIRECTION }
            views.setOnClickPendingIntent(R.id.directionButton, PendingIntent.getBroadcast(
                context, 3, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val gpsIntent = Intent(context, TrafficWidgetProvider::class.java).apply { action = ACTION_TOGGLE_GPS }
            views.setOnClickPendingIntent(R.id.gpsButton, PendingIntent.getBroadcast(
                context, 4, gpsIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            val routeToggleIntent = Intent(context, TrafficWidgetProvider::class.java).apply { action = ACTION_TOGGLE_ROUTE }
            views.setOnClickPendingIntent(R.id.altRouteInfo, PendingIntent.getBroadcast(
                context, 5, routeToggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

            // Load data and update display
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val trafficStatus = prefs.getInt(KEY_LAST_TRAFFIC_STATUS, TrafficStatus.UNKNOWN.ordinal)
                val duration = prefs.getInt(KEY_LAST_DURATION, 0)
                val durationTraffic = prefs.getInt(KEY_LAST_DURATION_TRAFFIC, 0)
                val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0)
                val lastError = prefs.getString(KEY_LAST_ERROR, null)
                val direction = prefs.getInt(KEY_DIRECTION, DIRECTION_TO_HOME)
                val useGps = prefs.getBoolean(KEY_USE_GPS, false)
                val showAlt = prefs.getBoolean(KEY_SHOW_ALT, false)

                // GPS button: green tint when active
                views.setInt(R.id.gpsButton, "setBackgroundColor",
                    if (useGps) Color.parseColor("#CC00AA44") else Color.parseColor("#55FFFFFF"))

                // Direction button label — show 📍 prefix when GPS mode is on
                val destLabel = if (direction == DIRECTION_TO_WORK) "🏢 Work" else "🏠 Home"
                views.setTextViewText(R.id.directionButton,
                    if (useGps) "📍→$destLabel" else "→$destLabel")

                // Status label
                val status = TrafficStatus.values()[trafficStatus]
                views.setTextViewText(R.id.statusLabel, when (status) {
                    TrafficStatus.GREEN -> "Clear"
                    TrafficStatus.YELLOW -> "Moderate"
                    TrafficStatus.RED -> "Heavy"
                    TrafficStatus.UNKNOWN -> "Unknown"
                })

                // Route map image — load from file saved by TrafficCheckWorker
                try {
                    val mapFile = java.io.File(context.filesDir, MAP_FILE_NAME)
                    if (mapFile.exists()) {
                        val mapBitmap = BitmapFactory.decodeFile(mapFile.absolutePath)
                        if (mapBitmap != null) views.setImageViewBitmap(R.id.mapImage, mapBitmap)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TrafficWidget", "Map image error", e)
                }

                // Gauge bitmap — own try/catch so a failure here never breaks the buttons
                try {
                    val ratio = if (duration > 0 && durationTraffic > 0)
                        durationTraffic.toFloat() / duration.toFloat() else 1.0f
                    views.setImageViewBitmap(R.id.gaugeImage, createGaugeBitmap(ratio))
                } catch (e: Exception) {
                    android.util.Log.e("TrafficWidget", "Gauge bitmap error", e)
                }

                // Travel time or error
                val altDurationTraffic = prefs.getInt(KEY_LAST_ALT_DURATION_TRAFFIC, 0)
                val altDuration = prefs.getInt(KEY_LAST_ALT_DURATION, altDurationTraffic)
                if (!lastError.isNullOrEmpty()) {
                    views.setTextViewText(R.id.travelTime, "Error")
                    views.setTextViewText(R.id.delayInfo, lastError)
                    views.setTextViewText(R.id.altRouteInfo, "")
                } else if (durationTraffic > 0) {
                    if (showAlt && altDurationTraffic > 0) {
                        // Showing alt route in info panel
                        val altMin = altDurationTraffic / 60
                        val altDelay = (altDurationTraffic - altDuration) / 60
                        views.setTextViewText(R.id.statusLabel, "Fastest (toll)")
                        views.setTextViewText(R.id.travelTime, "${altMin} min")
                        views.setTextViewText(R.id.delayInfo,
                            if (altDelay > 0) "+${altDelay} min delay" else "No delay")
                        val mainMin = durationTraffic / 60
                        views.setTextViewText(R.id.altRouteInfo, "← No Toll: ${mainMin} min (tap)")
                    } else {
                        // Showing primary route in info panel
                        val minutes = durationTraffic / 60
                        views.setTextViewText(R.id.travelTime, "${minutes} min")
                        val delay = (durationTraffic - duration) / 60
                        views.setTextViewText(R.id.delayInfo,
                            if (delay > 0) "+${delay} min delay" else "No delay")
                        if (altDurationTraffic > 0) {
                            val altMin = altDurationTraffic / 60
                            val altDelay = (altDurationTraffic - altDuration) / 60
                            val altDelayStr = if (altDelay > 0) " +${altDelay}m" else ""
                            views.setTextViewText(R.id.altRouteInfo, "🛣️ Fastest: ${altMin} min${altDelayStr} (tap)")
                        } else {
                            views.setTextViewText(R.id.altRouteInfo, "No faster route")
                        }
                    }
                } else {
                    views.setTextViewText(R.id.travelTime, "-- min")
                    views.setTextViewText(R.id.delayInfo, "Tap to configure")
                    views.setTextViewText(R.id.altRouteInfo, "")
                }

                // ETA — arrival time in 24h format
                val displayDuration = if (showAlt && altDurationTraffic > 0) altDurationTraffic else durationTraffic
                if (displayDuration > 0) {
                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.SECOND, displayDuration)
                    val etaStr = "%02d:%02d".format(
                        cal.get(java.util.Calendar.HOUR_OF_DAY),
                        cal.get(java.util.Calendar.MINUTE)
                    )
                    val prefix = if (showAlt && altDurationTraffic > 0) "Alt ETA" else "ETA"
                    views.setTextViewText(R.id.etaText, "$prefix  $etaStr")
                } else {
                    views.setTextViewText(R.id.etaText, "")
                }

                // Last update time
                if (lastUpdate > 0) {
                    val ago = (System.currentTimeMillis() - lastUpdate) / 60000
                    views.setTextViewText(R.id.lastUpdate, "Updated ${ago}m ago")
                }
            } catch (e: Exception) {
                android.util.Log.e("TrafficWidget", "Error updating widget display", e)
                views.setTextViewText(R.id.statusLabel, "Error")
                views.setTextViewText(R.id.travelTime, "!")
                views.setTextViewText(R.id.delayInfo, "Tap ⚙️ to configure")
            }

            // Always apply — buttons are set regardless of any errors above
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

        /**
         * Draws a semicircular speedometer gauge bitmap.
         * Arc: LEFT=GREEN (clear roads) → TOP=YELLOW → RIGHT=RED (heavy traffic).
         * Needle points based on traffic ratio (1.0=green/left, 2.0+=red/right).
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

            // Gradient arc aligned with traffic thresholds:
            //   GREEN zone: left 30% (ratio 1.0–1.15)
            //   YELLOW zone: middle 40% (ratio 1.15–1.35)
            //   RED zone: right 30% (ratio 1.35–1.5)
            val tGreen = 0.30f   // = (THRESHOLD_GREEN-1)/(maxRatio-1) = 0.15/0.5
            val tYellow = 0.70f  // = (THRESHOLD_YELLOW-1)/(maxRatio-1) = 0.35/0.5
            val segments = 60
            val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.BUTT
            }
            val segSweep = 180f / segments + 0.5f
            for (i in 0 until segments) {
                val t = i.toFloat() / (segments - 1)
                arcPaint.color = when {
                    t < tGreen -> Color.parseColor("#4CAF50")  // pure green
                    t < (tGreen + tYellow) / 2f -> interpolateColor(
                        Color.parseColor("#4CAF50"),
                        Color.parseColor("#FFC107"),
                        (t - tGreen) / ((tGreen + tYellow) / 2f - tGreen)
                    )
                    t < tYellow -> interpolateColor(
                        Color.parseColor("#FFC107"),
                        Color.parseColor("#F44336"),
                        (t - (tGreen + tYellow) / 2f) / (tYellow - (tGreen + tYellow) / 2f)
                    )
                    else -> Color.parseColor("#F44336")  // pure red
                }
                val startAngle = 180f + t * 180f
                canvas.drawArc(oval, startAngle, segSweep, false, arcPaint)
            }

            // Needle: maxRatio=1.5 so moderate traffic lands visually in yellow zone
            val maxRatio = 1.5f
            val fraction = ((ratio - 1f) / (maxRatio - 1f)).coerceIn(0f, 1f)
            val needleAngleRad = Math.toRadians(((1.0 - fraction) * 180.0))
            val needleLen = radius - 12f
            val nx = (cx + needleLen * cos(needleAngleRad)).toFloat()
            val ny = (cy - needleLen * sin(needleAngleRad)).toFloat()

            // Needle color matches its position on the arc
            val needleColor = when {
                fraction < tGreen -> Color.parseColor("#4CAF50")
                fraction < tYellow -> interpolateColor(
                    Color.parseColor("#FFC107"),
                    Color.parseColor("#FF5722"),
                    (fraction - tGreen) / (tYellow - tGreen)
                )
                else -> Color.parseColor("#F44336")
            }
            val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = needleColor
                strokeWidth = 5f
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
