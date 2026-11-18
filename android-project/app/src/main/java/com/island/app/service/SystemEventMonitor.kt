package com.island.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.island.app.model.IslandEvent
import com.island.app.model.RingerMode
import com.island.app.state.IslandStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SystemEventMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stateManager: IslandStateManager
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var lastChargingState: Boolean? = null
    private var lastBatteryLevel: Int = -1
    private var warned20 = false
    private var warned10 = false
    private var notifiedFull = false
    private var lastWifiConnected: Boolean? = null
    private var lastWifiSsid: String? = null
    private var lastHotspotEnabled: Boolean? = null
    private var lastRingerMode: Int = audioManager.ringerMode
    private var lastAirplaneMode: Boolean? = null
    private var isRegistered = false

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    val pct = readCurrentBatteryPercent()
                    lastChargingState = true
                    warned20 = false
                    warned10 = false
                    stateManager.postEvent(IslandEvent.ChargingStarted(pct))
                }

                Intent.ACTION_POWER_DISCONNECTED -> {
                    val pct = readCurrentBatteryPercent()
                    lastChargingState = false
                    notifiedFull = false
                    stateManager.postEvent(IslandEvent.ChargingStopped(pct))
                }

                Intent.ACTION_BATTERY_CHANGED -> handleBatteryChanged(intent)

                "android.net.wifi.WIFI_AP_STATE_CHANGED" -> handleHotspotChanged(intent)

                BluetoothDevice.ACTION_ACL_CONNECTED -> handleBluetoothEvent(intent, connected = true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> handleBluetoothEvent(intent, connected = false)

                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val enabled = intent.getBooleanExtra("state", isAirplaneModeOn())
                    if (lastAirplaneMode != enabled) {
                        lastAirplaneMode = enabled
                        stateManager.postEvent(IslandEvent.AirplaneModeChanged(enabled))
                    }
                }

                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, audioManager.ringerMode)
                    if (lastRingerMode != mode) {
                        lastRingerMode = mode
                        val mapped = when (mode) {
                            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
                            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
                            else -> RingerMode.RING
                        }
                        stateManager.postEvent(IslandEvent.RingerModeChanged(mapped))
                    }
                }

                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> handlePhoneState(intent)
            }
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val ssid = resolveCurrentWifiSsid()
            if (lastWifiConnected != true || lastWifiSsid != ssid) {
                lastWifiConnected = true
                lastWifiSsid = ssid
                stateManager.postEvent(IslandEvent.WifiConnected(ssid = ssid))
            }
        }

        override fun onLost(network: Network) {
            if (lastWifiConnected == true) {
                lastWifiConnected = false
                val prev = lastWifiSsid
                lastWifiSsid = null
                stateManager.postEvent(IslandEvent.WifiDisconnected(previousSsid = prev))
            }
        }
    }

    fun start() {
        if (isRegistered) return
        isRegistered = true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            systemReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback) }

        scope.launch {
            IslandEventBus.callCommands.collectLatest { cmd ->
                handleCallCommand(cmd)
            }
        }
    }

    fun stop() {
        if (!isRegistered) return
        isRegistered = false
        runCatching { context.unregisterReceiver(systemReceiver) }
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
    }

    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val percent = (level * 100) / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        if (lastBatteryLevel == -1) {
            lastBatteryLevel = percent
            lastChargingState = isCharging
            return
        }

        if (isCharging && (status == BatteryManager.BATTERY_STATUS_FULL || percent >= 100) && !notifiedFull) {
            notifiedFull = true
            stateManager.postEvent(IslandEvent.BatteryFull(percent))
        } else if (!isCharging) {
            notifiedFull = false
            if (percent <= 10 && !warned10) {
                warned10 = true
                warned20 = true
                stateManager.postEvent(IslandEvent.LowBattery(percent, isCritical = true))
            } else if (percent <= 20 && !warned20) {
                warned20 = true
                stateManager.postEvent(IslandEvent.LowBattery(percent, isCritical = false))
            } else if (percent > 22) {
                warned20 = false
                warned10 = false
            }
        }
        lastBatteryLevel = percent
        lastChargingState = isCharging
    }

    private fun handleHotspotChanged(intent: Intent) {
        val state = intent.getIntExtra("wifi_state", -1)
        // WIFI_AP_STATE_ENABLED = 13, WIFI_AP_STATE_DISABLED = 11
        val enabled = when (state) {
            13 -> true
            11 -> false
            else -> return
        }
        if (lastHotspotEnabled != enabled) {
            lastHotspotEnabled = enabled
            stateManager.postEvent(IslandEvent.HotspotChanged(enabled = enabled))
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothEvent(intent: Intent, connected: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stateManager.postEvent(
                IslandEvent.BluetoothChanged(
                    deviceName = "Bluetooth Device",
                    connected = connected,
                    isAudioDevice = true
                )
            )
            return
        }

        val device: BluetoothDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return

        val majorClass = device.bluetoothClass?.majorDeviceClass
        val deviceClass = device.bluetoothClass?.deviceClass
        val isAudio = majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER

        val name = device.name?.takeIf { it.isNotBlank() } ?: "Bluetooth Device"
        stateManager.postEvent(
            IslandEvent.BluetoothChanged(
                deviceName = name,
                connected = connected,
                isAudioDevice = isAudio
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun handlePhoneState(intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Private Number"
                stateManager.postEvent(
                    IslandEvent.IncomingCall(
                        callerName = number,
                        phoneNumber = number
                    )
                )
            }
            TelephonyManager.EXTRA_STATE_IDLE,
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                stateManager.clearIncomingCall()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCallCommand(command: CallCommand) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stateManager.clearIncomingCall()
            return
        }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
        runCatching {
            when (command) {
                CallCommand.Accept -> telecom.acceptRingingCall()
                CallCommand.Decline -> telecom.endCall()
            }
        }
        stateManager.clearIncomingCall()
    }

    private fun readCurrentBatteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return cap.coerceIn(0, 100)
    }

    @Suppress("DEPRECATION")
    private fun resolveCurrentWifiSsid(): String {
        val hasLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return "Wi-Fi Network"
        val raw = wifiManager.connectionInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"")
        return if (raw.isNullOrBlank() || raw == "<unknown ssid>") "Wi-Fi Network" else raw
    }

    private fun isAirplaneModeOn(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
}
