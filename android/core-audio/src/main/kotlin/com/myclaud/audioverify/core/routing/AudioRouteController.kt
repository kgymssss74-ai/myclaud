package com.myclaud.audioverify.core.routing

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

enum class Route { SPEAKER, BT, USB }

data class RoutePlan(
    val primary: AudioDeviceInfo?,
    val missingHints: List<String>,
)

class AudioRouteController(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val enumerator = DeviceEnumerator(context)

    fun listOutputs(): List<ClassifiedDevice> = enumerator.listOutputs()

    fun connectedRoutes(): Set<Route> {
        val kinds = enumerator.listOutputs().map { it.kind }.toSet()
        val routes = mutableSetOf<Route>()
        if (kinds.contains(RouteKind.SPEAKER)) routes += Route.SPEAKER
        if (kinds.contains(RouteKind.BT_A2DP)) routes += Route.BT
        if (kinds.contains(RouteKind.USB_HEADSET) || kinds.contains(RouteKind.USB_DEVICE)) {
            routes += Route.USB
        }
        return routes
    }

    fun resolve(route: Route): RoutePlan {
        val devices = enumerator.listOutputs()
        val speaker = devices.firstOrNull { it.kind == RouteKind.SPEAKER }?.info
        val bt = devices.firstOrNull { it.kind == RouteKind.BT_A2DP }?.info
        val usb = devices.firstOrNull { it.kind == RouteKind.USB_HEADSET || it.kind == RouteKind.USB_DEVICE }?.info

        return when (route) {
            Route.SPEAKER -> if (speaker != null) RoutePlan(speaker, emptyList())
            else RoutePlan(null, listOf("Built-in speaker not found"))
            Route.BT -> if (bt != null) RoutePlan(bt, emptyList())
            else RoutePlan(null, listOf("BT A2DP device not connected"))
            Route.USB -> if (usb != null) RoutePlan(usb, emptyList())
            else RoutePlan(null, listOf("USB headset not connected"))
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
