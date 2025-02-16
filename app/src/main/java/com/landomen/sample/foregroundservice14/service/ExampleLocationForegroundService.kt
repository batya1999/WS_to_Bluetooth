package com.landomen.sample.foregroundservice14.service

import android.Manifest
import kotlin.time.Duration.Companion.seconds
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Simple foreground service that shows a notification to the user and provides location updates.
 */
class ExampleLocationForegroundService : Service() {
    private val binder = LocalBinder()
    private lateinit var bleAd: BluetoothAdapter
    private var thread: Thread? = null
    private var run = true
    private lateinit var database: FirebaseDatabase
    private lateinit var dbReference: DatabaseReference
    val device_name = "2_dji_remote__" // Name of the DJI Remote device
    val targetDeviceAddress = "A4:CF:12:03:CF:4E" // Replace with your device address

    var state = MutableLiveData("")

    // UUID for creating RFCOMM socket (keeping original as requested)
    private val uuid = UUID.fromString("34df14f4-d5fc-4725-99b5-17baf9fc3304")

    // BLE connection variables
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnected = false

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
        bleAd = BluetoothAdapter.getDefaultAdapter()
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        bleAd.enable()
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
                Log.d(TAG, "Discovered BLE device: $deviceInfo")

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
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "Connected to GATT server.")
                        isConnected = true
                        state.postValue("Connected to device: ${device.address}")
                        gatt?.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "Disconnected from GATT server.")
                        isConnected = false
                        state.postValue("Disconnected from device: ${device.address}")
                        bluetoothGatt = null
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "Services discovered successfully")
                    }
                }
            }

            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Could not connect to device: ${e.message}")
            state.postValue("Connection failed: ${e.message}")
        }
    }

    private fun closeBluetoothConnection() {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                return
            }
            bluetoothGatt?.close()
            bluetoothGatt = null
            isConnected = false
            Log.d(TAG, "Bluetooth connection closed")
        } catch (e: Exception) {
            Log.e(TAG, "Could not close Bluetooth connection: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendDataToDevice(data: String) {
        // Check if BLE is connected
        if (bluetoothGatt == null || !isConnected) {
            Log.e(TAG, "Bluetooth is not connected")
            return
        }

        try {
            // Extract the 4 values (first 16 chars of the string, 4 chars each)
            val values = data.take(16).chunked(4).take(4).map {
                it.padEnd(4, '0').toIntOrNull() ?: 0
            }

            val throttle = values.getOrElse(0) { 0 }
            val yaw = values.getOrElse(1) { 0 }
            val pitch = values.getOrElse(2) { 0 }
            val roll = values.getOrElse(3) { 0 }

            // Find a writable characteristic
            for (service in bluetoothGatt?.services ?: emptyList()) {
                for (characteristic in service.characteristics) {
                    if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                        // Pack the data into a binary format
                        val byteData = ByteBuffer.allocate(8)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putShort(throttle.toShort())
                            .putShort(yaw.toShort())
                            .putShort(pitch.toShort())
                            .putShort(roll.toShort())
                            .array()

                        characteristic.value = byteData
                        val success = bluetoothGatt?.writeCharacteristic(characteristic) ?: false
                        if (success) {
                            Log.d(TAG, "Data sent to device: $data")
                            return
                        }
                    }
                }
            }
            Log.e(TAG, "No writable characteristic found")
        } catch (e: Exception) {
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
