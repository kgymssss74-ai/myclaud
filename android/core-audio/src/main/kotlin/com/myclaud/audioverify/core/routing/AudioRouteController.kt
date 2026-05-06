package com.myclaud.audioverify.core.routing

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

enum class Route { SPEAKER, BT, USB, SPEAKER_PLUS_BT, SPEAKER_PLUS_USB }

data class RoutePlan(
    val primary: AudioDeviceInfo?,
    val secondary: AudioDeviceInfo?,
    val missingHints: List<String>,
)

/**
 * Resolves a [Route] enum into concrete [AudioDeviceInfo] handles to pin per
 * track. When a required device isn't connected, the resolution returns
 * [missingHints] so the runner can prompt the operator (without auto-relaxing).
 */
class AudioRouteController(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val enumerator = DeviceEnumerator(context)

    fun resolve(route: Route): RoutePlan {
        val devices = enumerator.listOutputs()
        val speaker = devices.firstOrNull { it.kind == RouteKind.SPEAKER }?.info
        val bt = devices.firstOrNull { it.kind == RouteKind.BT_A2DP }?.info
        val usb = devices.firstOrNull { it.kind == RouteKind.USB_HEADSET || it.kind == RouteKind.USB_DEVICE }?.info

        val missing = mutableListOf<String>()
        return when (route) {
            Route.SPEAKER -> RoutePlan(speaker, null, if (speaker == null) listOf("Built-in speaker not found").also { missing += it } else emptyList())
            Route.BT -> {
                if (bt == null) missing += "BT A2DP device not connected — pair a Bluetooth headset"
                RoutePlan(bt, null, missing)
            }
            Route.USB -> {
                if (usb == null) missing += "USB headset not connected — plug a USB-C audio device"
                RoutePlan(usb, null, missing)
            }
            Route.SPEAKER_PLUS_BT -> {
                if (speaker == null) missing += "Built-in speaker not found"
                if (bt == null) missing += "BT A2DP device not connected"
                RoutePlan(speaker, bt, missing)
            }
            Route.SPEAKER_PLUS_USB -> {
                if (speaker == null) missing += "Built-in speaker not found"
                if (usb == null) missing += "USB headset not connected"
                RoutePlan(speaker, usb, missing)
            }
        }
    }

    fun registerHotplug(callback: () -> Unit): AudioDeviceCallback {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = callback()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = callback()
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        return cb
    }

    fun unregisterHotplug(cb: AudioDeviceCallback) {
        am.unregisterAudioDeviceCallback(cb)
    }
}
