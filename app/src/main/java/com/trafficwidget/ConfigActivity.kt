package com.trafficwidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.trafficwidget.databinding.ActivityConfigBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding
    private var appWidgetId = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    private var isWidgetConfiguration = false

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                Toast.makeText(this, "Location permission granted", Toast.LENGTH_SHORT).show()
            }
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                Toast.makeText(this, "Coarse location permission granted", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(this, "Location permission required for widget", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set result to CANCELED initially. If user backs out, widget won't be added
        setResult(RESULT_CANCELED)

        // Check if this is widget configuration or just settings
        val extras = intent.extras
        if (extras != null) {
            appWidgetId = extras.getInt(
                android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
                android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
            )
        }
        isWidgetConfiguration = appWidgetId != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID

        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupClickListeners()
        checkPermissions()

        // If widget configuration and already configured, auto-finish
        if (isWidgetConfiguration) {
            checkAndAutoFinishConfiguration()
        }
    }
    
    private fun loadSettings() {
        val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
        
        binding.apiKeyInput.setText(prefs.getString(TrafficWidgetProvider.KEY_API_KEY, TrafficWidgetProvider.DEFAULT_API_KEY))
        binding.homeAddressInput.setText(prefs.getString(TrafficWidgetProvider.KEY_HOME_ADDRESS, TrafficWidgetProvider.DEFAULT_HOME_ADDRESS))
        
        val homeLat = prefs.getString(TrafficWidgetProvider.KEY_HOME_LAT, null)
        val homeLng = prefs.getString(TrafficWidgetProvider.KEY_HOME_LNG, null)
        if (homeLat != null && homeLng != null) {
            binding.coordinatesText.text = "📍 $homeLat, $homeLng"
        } else {
            binding.coordinatesText.text = "📍 Not set"
        }
        
        // Load threshold values
        val greenThreshold = ((TrafficWidgetProvider.THRESHOLD_GREEN - 1) * 100).toInt()
        val yellowThreshold = ((TrafficWidgetProvider.THRESHOLD_YELLOW - 1) * 100).toInt()
        binding.thresholdInfo.text = "🟢 Green: <${greenThreshold}% delay\n🟡 Yellow: ${greenThreshold}-${yellowThreshold}% delay\n🔴 Red: >${yellowThreshold}% delay"
    }
    
    private fun setupClickListeners() {
        binding.saveApiKeyButton.setOnClickListener {
            val apiKey = binding.apiKeyInput.text.toString().trim()
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Please enter an API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
            prefs.edit()
                .putString(TrafficWidgetProvider.KEY_API_KEY, apiKey)
                .apply()
            
            Toast.makeText(this, "API key saved!", Toast.LENGTH_SHORT).show()
        }
        
        binding.saveAddressButton.setOnClickListener {
            val address = binding.homeAddressInput.text.toString().trim()
            val apiKey = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
                .getString(TrafficWidgetProvider.KEY_API_KEY, TrafficWidgetProvider.DEFAULT_API_KEY)
            
            if (address.isEmpty()) {
                Toast.makeText(this, "Please enter an address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (apiKey.isNullOrEmpty()) {
                Toast.makeText(this, "Please set API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Geocode the address
            binding.saveAddressButton.isEnabled = false
            binding.saveAddressButton.text = "Looking up..."
            
            lifecycleScope.launch {
                val result = geocodeAddress(address, apiKey)
                if (result != null) {
                    saveHomeLocation(result.first, result.second, address)
                    Toast.makeText(this@ConfigActivity, "Home location saved!", Toast.LENGTH_SHORT).show()
                }
                // Error message already shown in geocodeAddress function
                binding.saveAddressButton.isEnabled = true
                binding.saveAddressButton.text = "Save Address"
            }
        }
        
        binding.testButton.setOnClickListener {
            val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
            val apiKey = prefs.getString(TrafficWidgetProvider.KEY_API_KEY, TrafficWidgetProvider.DEFAULT_API_KEY)
            val homeLat = prefs.getString(TrafficWidgetProvider.KEY_HOME_LAT, "")

            when {
                apiKey.isNullOrEmpty() -> {
                    Toast.makeText(this, "Please set API key first", Toast.LENGTH_SHORT).show()
                }
                homeLat.isNullOrEmpty() -> {
                    Toast.makeText(this, "Please set home address first", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    // Show testing message
                    Toast.makeText(this, "Testing traffic check... Check widget in 10-15 seconds", Toast.LENGTH_LONG).show()
                    TrafficCheckWorker.enqueueNow(this)

                    // Show a dialog with status after a delay
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(15000) // Wait 15 seconds for worker to complete
                        val lastError = prefs.getString(TrafficWidgetProvider.KEY_LAST_ERROR, null)
                        val lastUpdate = prefs.getLong(TrafficWidgetProvider.KEY_LAST_UPDATE, 0)
                        val trafficStatus = prefs.getInt(TrafficWidgetProvider.KEY_LAST_TRAFFIC_STATUS, -1)

                        val message = if (lastError != null) {
                            "❌ Test Failed:\n$lastError"
                        } else if (trafficStatus >= 0 && lastUpdate > 0) {
                            val status = when (trafficStatus) {
                                1 -> "🟢 GREEN - Clear roads"
                                2 -> "🟡 YELLOW - Moderate traffic"
                                3 -> "🔴 RED - Heavy traffic"
                                else -> "⚪ UNKNOWN"
                            }
                            "✅ Test Successful!\nStatus: $status\n\nCheck your home screen widget!"
                        } else {
                            "⏳ Test in progress...\nCheck widget on home screen"
                        }

                        androidx.appcompat.app.AlertDialog.Builder(this@ConfigActivity)
                            .setTitle("Test Results")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
        
        binding.helpButton.setOnClickListener {
            showHelpDialog()
        }

        binding.doneButton.setOnClickListener {
            // Simply close the activity
            Toast.makeText(this, "✅ Configuration saved!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private suspend fun geocodeAddress(address: String, apiKey: String): Pair<String, String>? {
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
                    // Show specific error message
                    val errorMessage = when (status) {
                        "REQUEST_DENIED" -> {
                            val error = json.optString("error_message", "API request denied. Check if Geocoding API is enabled.")
                            android.util.Log.e("ConfigActivity", "Geocoding error: $error")
                            error
                        }
                        "ZERO_RESULTS" -> "Address not found. Please try a different address."
                        "OVER_QUERY_LIMIT" -> "API quota exceeded. Please try again later."
                        "INVALID_REQUEST" -> "Invalid address format."
                        else -> "Error: $status"
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ConfigActivity, errorMessage, Toast.LENGTH_LONG).show()
                    }
                    return@withContext null
                }

                val results = json.getJSONArray("results")
                if (results.length() == 0) {
                    return@withContext null
                }

                val location = results.getJSONObject(0)
                    .getJSONObject("geometry")
                    .getJSONObject("location")

                val lat = location.getDouble("lat").toString()
                val lng = location.getDouble("lng").toString()

                Pair(lat, lng)
            } catch (e: java.net.UnknownHostException) {
                android.util.Log.e("ConfigActivity", "Network error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ConfigActivity, "Network error. Check your internet connection.", Toast.LENGTH_LONG).show()
                }
                null
            } catch (e: java.net.SocketTimeoutException) {
                android.util.Log.e("ConfigActivity", "Timeout error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ConfigActivity, "Request timeout. Please try again.", Toast.LENGTH_LONG).show()
                }
                null
            } catch (e: Exception) {
                android.util.Log.e("ConfigActivity", "Geocoding error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ConfigActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
                null
            }
        }
    }
    
    private fun saveHomeLocation(lat: String, lng: String, address: String) {
        val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(TrafficWidgetProvider.KEY_HOME_LAT, lat)
            .putString(TrafficWidgetProvider.KEY_HOME_LNG, lng)
            .putString(TrafficWidgetProvider.KEY_HOME_ADDRESS, address)
            .apply()

        binding.coordinatesText.text = "📍 $lat, $lng"

        // Trigger immediate traffic check
        TrafficCheckWorker.enqueueNow(this)

        // If this is widget configuration, finish and add widget
        if (isWidgetConfiguration) {
            finishWidgetConfiguration()
        }
    }

    private fun checkAndAutoFinishConfiguration() {
        // Check if already configured
        val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
        val apiKey = prefs.getString(TrafficWidgetProvider.KEY_API_KEY, TrafficWidgetProvider.DEFAULT_API_KEY)
        val homeLat = prefs.getString(TrafficWidgetProvider.KEY_HOME_LAT, "")

        if (!apiKey.isNullOrEmpty() && !homeLat.isNullOrEmpty()) {
            // Already configured! Show message and auto-finish after short delay
            Toast.makeText(this, "✅ Already configured! Adding widget...", Toast.LENGTH_LONG).show()

            // Delay slightly so user sees the message
            binding.root.postDelayed({
                try {
                    finishWidgetConfiguration()
                } catch (e: Exception) {
                    android.util.Log.e("ConfigActivity", "Error finishing widget config", e)
                    Toast.makeText(this, "Error adding widget: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }, 1500)
        } else {
            // Not configured, show message
            Toast.makeText(this, "Please configure API key and home address", Toast.LENGTH_LONG).show()
        }
    }

    private fun finishWidgetConfiguration() {
        try {
            // Check if we have minimum configuration
            val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
            val apiKey = prefs.getString(TrafficWidgetProvider.KEY_API_KEY, TrafficWidgetProvider.DEFAULT_API_KEY)
            val homeLat = prefs.getString(TrafficWidgetProvider.KEY_HOME_LAT, "")

            if (apiKey.isNullOrEmpty() || homeLat.isNullOrEmpty()) {
                Toast.makeText(this, "Please complete setup: API key and home address required", Toast.LENGTH_LONG).show()
                return
            }

            android.util.Log.d("ConfigActivity", "Finishing widget config for ID: $appWidgetId")

            // Update the widget
            val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(this)
            TrafficWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)

            // Return result
            val resultValue = android.content.Intent().apply {
                putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            setResult(RESULT_OK, resultValue)

            android.util.Log.d("ConfigActivity", "Widget configuration completed successfully")
            finish()
        } catch (e: Exception) {
            android.util.Log.e("ConfigActivity", "Error in finishWidgetConfiguration", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            // Still try to finish with OK result
            val resultValue = android.content.Intent().apply {
                putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }
    
    override fun onBackPressed() {
        // If widget configuration and configured, finish properly
        if (isWidgetConfiguration) {
            val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
            val apiKey = prefs.getString(TrafficWidgetProvider.KEY_API_KEY, TrafficWidgetProvider.DEFAULT_API_KEY)
            val homeLat = prefs.getString(TrafficWidgetProvider.KEY_HOME_LAT, "")

            if (!apiKey.isNullOrEmpty() && !homeLat.isNullOrEmpty()) {
                // Configured - add the widget
                finishWidgetConfiguration()
                return
            }
        }
        // Not configured or not widget config - just close
        super.onBackPressed()
    }

    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
            }
            else -> {
                locationPermissionRequest.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }
    
    private fun showHelpDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Setup Instructions")
            .setMessage("""
                1. Get a Google Maps API key:
                   • Go to console.cloud.google.com
                   • Create a project
                   • Enable "Routes API" and "Geocoding API"
                   • Create an API key
                   
                2. Enter your API key above
                
                3. Type your home address and save
                
                4. Add the widget to your home screen
                
                The widget will:
                • Check traffic every 15 minutes
                • Show 🟢 green if <15% delay
                • Show 🟡 yellow if 15-35% delay
                • Show 🔴 red if >35% delay
                
                Tap the gauge to open Waze/Maps navigation!
            """.trimIndent())
            .setPositiveButton("Got it", null)
            .show()
    }
}
