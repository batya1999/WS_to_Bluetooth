package com.landomen.sample.foregroundservice14.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import com.landomen.sample.foregroundservice14.notification.NotificationsHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class BluetoothForegroundService : Service() {
    private val binder = LocalBinder()
    private lateinit var bleAd: BluetoothAdapter
    private var thread: Thread? = null
    private var run = true
    val device_name = "5_dji_remote__" // Name of the DJI Remote device AVATA
    val targetDeviceAddress = "A4:CF:12:05:2E:1E" // avata version (__dji_remote__)
    var state = MutableLiveData("")

    private val uuid = UUID.fromString("104f2220-2777-4a0b-9edc-786a1e9c6bd1") //avata

    private val client = OkHttpClient()
    private lateinit var webSocket: WebSocket

    // BLE connection variables
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnected = false

    // Handler for data transmission
    private val handler = Handler(Looper.getMainLooper())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var latestJoystickData: String = ""

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothForegroundService = this@BluetoothForegroundService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForegroundService()

        // Start a thread for Bluetooth communication
        if (thread == null || !thread!!.isAlive) {
            thread = Thread(this::main)
            run = true
            thread!!.start()
        }

        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        bleAd = BluetoothAdapter.getDefaultAdapter()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        bleAd.enable()

        Log.d(TAG, "Bluetooth Service initialized")
        Toast.makeText(this, "Bluetooth Service created", Toast.LENGTH_SHORT).show()

        // Start listening to WebSocket
        connectWebSocket()
    }

    override fun onDestroy() {
        super.onDestroy()
        run = false
        closeBluetoothConnection()
//        webSocket.close(1000, "App closed")
        Toast.makeText(this, "Bluetooth Service destroyed", Toast.LENGTH_SHORT).show()
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
                    bluetoothLeScanner.stopScan(this) // Stop scanning once the target device is found
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
            }
        }

        while (run && !isConnected) {
            bluetoothLeScanner.startScan(scanCallback)
            while (!found && run && !isConnected) {
                Thread.sleep(100)
            }
            bluetoothLeScanner.stopScan(scanCallback)

            if (!run) break // Ensure we exit if run is set to false

            if (targetDevice != null) {
                state.postValue("Target BLE device found: ${targetDevice!!.address}")
                connectToDevice(targetDevice!!) // Connect to the target device
            }

            // Reset found flag to continue scanning if connection is lost
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

                        // Try to reconnect automatically after a delay
                        if (run) {
                            handler.postDelayed({
                                reconnectToDevice(device)
                            }, 5000) // Delay before trying to reconnect (e.g., 5 seconds)
                        }
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

    // Function to handle reconnection attempt after a disconnection
    private fun reconnectToDevice(device: BluetoothDevice) {
        if (!isConnected) {
            Log.d(TAG, "Attempting to reconnect to device: ${device.address}")
            connectToDevice(device)  // Try to reconnect
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
        if (bluetoothGatt == null || !isConnected) {
            Log.e(TAG, "Bluetooth is not connected")
            return
        }

        Log.e(TAG, "Received ${data}")

        try {
            // Extract 6 values from the data string (each value is 3 characters)
            val values = data.split(",")
            val roll = values[0].toShort()
            val pitch = values[1].toShort()
            val yaw = values[2].toShort()
            val throttle = values[3].toShort()
            val camera = values[4].toShort()
            val mode = values[5].toShort()

            // Pack the data into a 12-byte array (little-endian)
            val byteData = ByteBuffer.allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(pitch)
                .putShort(roll)
                .putShort(throttle)
                .putShort(yaw)
                .putShort(camera)
                .putShort(mode)
                .array()

            // Find a writable characteristic
            for (service in bluetoothGatt?.services ?: emptyList()) {
                for (characteristic in service.characteristics) {
                    if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
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

    // Connect to the WebSocket server
    private fun connectWebSocket() {
        val request = Request.Builder().url("ws://82.81.197.132:5000/drone").build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                Log.e(TAG, "WebSocket opened successfully")
            }

            @RequiresApi(Build.VERSION_CODES.Q)
            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                Log.d(TAG, "Received WebSocket message: $text")

                // Directly send the received message to the Bluetooth device
                mainHandler.post {
                    if (isConnected) {
                        // Assuming the WebSocket message is in the same format as the previous joystick data
                        // If the format is different, you'll need to parse or transform the message accordingly
                        sendDataToDevice(text)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                Log.e(TAG, "WebSocket connection failed: ${t.message}")

                // Attempt to reconnect
                mainHandler.postDelayed({
                    connectWebSocket()
                }, 5000)
            }
        }
        webSocket = client.newWebSocket(request, listener)
        Log.e(TAG, "Connected to websocket")
    }

    private fun startAsForegroundService() {
        NotificationsHelper.createNotificationChannel(this)

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

    fun stopForegroundService() {
        run = false
        stopSelf()
    }

    companion object {
        private const val TAG = "BluetoothForegroundService"
    }
}