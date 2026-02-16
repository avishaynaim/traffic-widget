package com.trafficwidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.Status
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.trafficwidget.databinding.ActivityConfigBinding

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
        setupPlacesAutocomplete()
        setupClickListeners()
        checkPermissions()
    }
    
    private fun loadSettings() {
        val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
        
        binding.apiKeyInput.setText(prefs.getString(TrafficWidgetProvider.KEY_API_KEY, ""))
        binding.homeAddressText.text = prefs.getString(TrafficWidgetProvider.KEY_HOME_ADDRESS, "Not set")
        
        // Load threshold values
        val greenThreshold = ((TrafficWidgetProvider.THRESHOLD_GREEN - 1) * 100).toInt()
        val yellowThreshold = ((TrafficWidgetProvider.THRESHOLD_YELLOW - 1) * 100).toInt()
        binding.thresholdInfo.text = "🟢 Green: <${greenThreshold}% delay\n🟡 Yellow: <${yellowThreshold}% delay\n🔴 Red: >${yellowThreshold}% delay"
    }
    
    private fun setupPlacesAutocomplete() {
        val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
        val apiKey = prefs.getString(TrafficWidgetProvider.KEY_API_KEY, "")
        
        if (!apiKey.isNullOrEmpty()) {
            initializePlaces(apiKey)
        }
    }
    
    private fun initializePlaces(apiKey: String) {
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, apiKey)
        }
        
        val autocompleteFragment = supportFragmentManager.findFragmentById(R.id.autocomplete_fragment)
                as? AutocompleteSupportFragment
        
        autocompleteFragment?.apply {
            setPlaceFields(listOf(
                Place.Field.ID,
                Place.Field.NAME,
                Place.Field.ADDRESS,
                Place.Field.LAT_LNG
            ))
            setHint("Search for your home address")
            
            setOnPlaceSelectedListener(object : PlaceSelectionListener {
                override fun onPlaceSelected(place: Place) {
                    val latLng = place.latLng
                    if (latLng != null) {
                        saveHomeLocation(
                            lat = latLng.latitude.toString(),
                            lng = latLng.longitude.toString(),
                            address = place.address ?: place.name ?: "Home"
                        )
                    }
                }
                
                override fun onError(status: Status) {
                    Toast.makeText(
                        this@ConfigActivity,
                        "Error: ${status.statusMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }
    }
    
    private fun saveHomeLocation(lat: String, lng: String, address: String) {
        val prefs = getSharedPreferences(TrafficWidgetProvider.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(TrafficWidgetProvider.KEY_HOME_LAT, lat)
            .putString(TrafficWidgetProvider.KEY_HOME_LNG, lng)
            .putString(TrafficWidgetProvider.KEY_HOME_ADDRESS, address)
            .apply()
        
        binding.homeAddressText.text = address
        Toast.makeText(this, "Home location saved!", Toast.LENGTH_SHORT).show()
        
        // Trigger immediate traffic check
        TrafficCheckWorker.enqueueNow(this)
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
            
            // Initialize Places with new key
            initializePlaces(apiKey)
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
                   • Enable "Directions API" and "Places API"
                   • Create an API key
                   
                2. Enter your API key above
                
                3. Search for your home address
                
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
