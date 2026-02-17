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
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        loadSettings()
        setupClickListeners()
        checkPermissions()
    }
    
    private fun loadSettings() {
        val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
        
        binding.apiKeyInput.setText(prefs.getString(TrafficWidgetProvider.KEY_API_KEY, ""))
        binding.homeAddressInput.setText(prefs.getString(TrafficWidgetProvider.KEY_HOME_ADDRESS, ""))
        
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
                .getString(TrafficWidgetProvider.KEY_API_KEY, "")
            
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
                } else {
                    Toast.makeText(this@ConfigActivity, "Could not find address", Toast.LENGTH_SHORT).show()
                }
                binding.saveAddressButton.isEnabled = true
                binding.saveAddressButton.text = "Save Address"
            }
        }
        
        binding.testButton.setOnClickListener {
            val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
            val apiKey = prefs.getString(TrafficWidgetProvider.KEY_API_KEY, "")
            val homeLat = prefs.getString(TrafficWidgetProvider.KEY_HOME_LAT, "")
            
            when {
                apiKey.isNullOrEmpty() -> {
                    Toast.makeText(this, "Please set API key first", Toast.LENGTH_SHORT).show()
                }
                homeLat.isNullOrEmpty() -> {
                    Toast.makeText(this, "Please set home address first", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Toast.makeText(this, "Testing traffic check...", Toast.LENGTH_SHORT).show()
                    TrafficCheckWorker.enqueueNow(this)
                }
            }
        }
        
        binding.helpButton.setOnClickListener {
            showHelpDialog()
        }
    }
    
    private suspend fun geocodeAddress(address: String, apiKey: String): Pair<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val encodedAddress = URLEncoder.encode(address, "UTF-8")
                val url = URL("https://maps.googleapis.com/maps/api/geocode/json?address=$encodedAddress&key=$apiKey")
                val response = url.readText()
                val json = JSONObject(response)
                
                if (json.getString("status") != "OK") {
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
            } catch (e: Exception) {
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
