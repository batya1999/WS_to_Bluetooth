package com.landomen.sample.foregroundservice14.service

import android.Manifest
import kotlin.time.Duration.Companion.seconds
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.landomen.sample.foregroundservice14.notification.NotificationsHelper
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Simple foreground service that shows a notification to the user and provides location updates.
 */
class ExampleLocationForegroundService : Service() {
    private val binder = LocalBinder()

    private var thread: Thread? = null
    private var run = true
    private lateinit var database: FirebaseDatabase
    private lateinit var dbReference: DatabaseReference
    val device_name = "2_dji_remote__" // Name of the DJI Remote device
    val targetDeviceAddress = "A4:CF:12:03:CF:4E" // Replace with your device address

    var state = MutableLiveData("")

    // Bluetooth related variables
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val uuid = UUID.fromString("34df14f4-d5fc-4725-99b5-17baf9fc3304") // Standard SerialPortService ID

    inner class LocalBinder : Binder() {
        fun getService(): ExampleLocationForegroundService = this@ExampleLocationForegroundService
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        startAsForegroundService()

        // Start a thread here
        if (thread == null || !thread!!.isAlive) {
            thread = Thread(this::main)
            run = true
            thread!!.start()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        database = FirebaseDatabase.getInstance()
        dbReference = database.getReference("bluetooth_connections")

        if (FirebaseApp.getApps(applicationContext).isEmpty()) {
            FirebaseApp.initializeApp(applicationContext)
        }

        Log.d(TAG, "Firebase Database initialized")
        Toast.makeText(this, "Foreground Service created", Toast.LENGTH_SHORT).show()

        // Start listening to joystick data
        setupDatabaseListener()
        Log.d(TAG, "onCreate")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        // Stop the thread here
        run = false

        // Close the Bluetooth connection
        closeBluetoothConnection()

        Toast.makeText(this, "Foreground Service destroyed", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    fun main() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter
        val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        if (bluetoothLeScanner == null) {
            Log.e(TAG, "Bluetooth LE Scanner not available")
            return
        }

        var targetDevice: BluetoothDevice? = null
        var found = false

        val scanCallback = object : android.bluetooth.le.ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val deviceInfo = "${device.name ?: "Unknown"} (${device.address})"
                val uuid = device.uuids
                Log.d(TAG, "Discovered BLE device: $deviceInfo")
//                device.uuids

                // Check if the device name matches the DJI Remote device name
                if (device.name == device_name && device.address == targetDeviceAddress) {
                    targetDevice = device
                    found = true
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
            }
        }

        while (run) {
            bluetoothLeScanner.startScan(scanCallback)
            while (!found && run) { // Check run in the loop condition
                Thread.sleep(100)
            }
            bluetoothLeScanner.stopScan(scanCallback)

            if (!run) break // Ensure we exit if run is set to false

            if (targetDevice != null) {
                state.postValue("Target BLE device found: ${targetDevice!!.address}")
                // Connect to the target device
                connectToDevice(targetDevice!!)
            }

            // Reset found flag to continue scanning
            found = false
        }

        // Ensure scanner stops when service is stopping
        bluetoothLeScanner.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
            bluetoothSocket?.connect()
            outputStream = bluetoothSocket?.outputStream
            Log.d(TAG, "Connected to device: ${device.address}")
            state.postValue("Connected to device: ${device.address}")
        } catch (e: IOException) {
            Log.e(TAG, "Could not connect to device: ${e.message}")
            state.postValue("Connection failed: ${e.message}")
        }
    }

    private fun closeBluetoothConnection() {
        try {
            bluetoothSocket?.close()
            outputStream?.close()
            Log.d(TAG, "Bluetooth connection closed")
        } catch (e: IOException) {
            Log.e(TAG, "Could not close Bluetooth connection: ${e.message}")
        }
    }

    private fun sendDataToDevice(data: String) {
        // Check if the Bluetooth socket is properly connected and output stream is initialized
        if (bluetoothSocket == null || !bluetoothSocket!!.isConnected) {
            Log.e(TAG, "Bluetooth socket is not connected")
            return
        }

        // If output stream is null, attempt to initialize it
//        if (outputStream == null) {
//            try {
//                outputStream = bluetoothSocket?.outputStream
//                if (outputStream == null) {
//                    Log.e(TAG, "Failed to initialize output stream")
//                    return
//                }
//            } catch (e: IOException) {
//                Log.e(TAG, "Error initializing output stream: ${e.message}")
//                return
//            }
//        }

        // Now safely send data to the output stream
        try {
            outputStream?.write(data.toByteArray())
            Log.d(TAG, "Data sent to device: $data")
        } catch (e: IOException) {
            Log.e(TAG, "Error sending data to device: ${e.message}")
        }
    }



    private fun setupDatabaseListener() {
        val joystickRef = FirebaseDatabase.getInstance().getReference("joystick_data/readable")

        joystickRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val joystickValues = mutableMapOf<Int, Int>()

                    for (child in snapshot.children) {
                        val key = child.key?.toIntOrNull() // Convert key to integer (0,1,2,...)
                        val value = child.getValue(Int::class.java) // Read the joystick value

                        if (key != null && value != null) {
                            joystickValues[key] = value
                        }
                    }

                    if (joystickValues.isNotEmpty()) {
                        // Extract exactly six values sorted by key
                        val formattedOutput = joystickValues.entries
                            .sortedBy { it.key }
                            .take(6)
                            .joinToString("") { it.value.toString() } // No separation

                        Log.d(TAG, "Joystick Data: $formattedOutput")

                        // Display only the formatted output
                        Toast.makeText(applicationContext, formattedOutput, Toast.LENGTH_SHORT).show()

                        // Send the formatted output to the connected Bluetooth device
                        sendDataToDevice(formattedOutput)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Database error: ${error.message}")
            }
        })
    }

    /**
     * Promotes the service to a foreground service, showing a notification to the user.
     *
     * This needs to be called within 10 seconds of starting the service or the system will throw an exception.
     */
    private fun startAsForegroundService() {
        // create the notification channel
        NotificationsHelper.createNotificationChannel(this)

        // promote service to foreground service
        ServiceCompat.startForeground(
            this,
            1,
            NotificationsHelper.buildNotification(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )
    }

    /**
     * Stops the foreground service and removes the notification.
     * Can be called from inside or outside the service.
     */
    fun stopForegroundService() {
        run = false
        stopSelf()
    }

    companion object {
        private const val TAG = "ExampleForegroundService"
        private val LOCATION_UPDATES_INTERVAL_MS = 1.seconds.inWholeMilliseconds
        private val TICKER_PERIOD_SECONDS = 5.seconds
    }
}
