package com.landomen.sample.foregroundservice14.service

import android.Manifest
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
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import com.landomen.sample.foregroundservice14.notification.NotificationsHelper
import kotlin.time.Duration.Companion.seconds

/**
 * Simple foreground service that shows a notification to the user and provides location updates.
 */
class ExampleLocationForegroundService : Service() {
    private val binder = LocalBinder()

    private var thread: Thread? = null;
    private var run = true;
    //    private lateinit var scan : BluetoothSocket
    val device_name = "__dji_remote__"

    var state = MutableLiveData("")

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
            thread = Thread(this::main);
            run = true;
            thread!!.start()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        Toast.makeText(this, "Foreground Service created", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        // Stop the thread here
        run = false;

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
        var arduino: BluetoothDevice? = null
        var found = false

        val scanCallback = object : android.bluetooth.le.ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val deviceInfo = "${device.name ?: "Unknown"} (${device.address})"
                Log.d(TAG, "Discovered BLE device: $deviceInfo")
                if (device.name == device_name){
                    arduino = device
                    found = true;
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
            }
        }


        while (run) {
            bluetoothLeScanner.startScan(scanCallback)
            while (!found)
                Thread.sleep(100) // Scan for 10 seconds
            bluetoothLeScanner.stopScan(scanCallback)
            state.postValue("DJI BLE found!")


        }
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
        run = false;
        stopSelf()
    }

    companion object {
        private const val TAG = "ExampleForegroundService"
        private val LOCATION_UPDATES_INTERVAL_MS = 1.seconds.inWholeMilliseconds
        private val TICKER_PERIOD_SECONDS = 5.seconds
    }
}
