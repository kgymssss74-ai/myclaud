package com.myclaud.audioverify.core.routing

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

enum class RouteKind { SPEAKER, BT_A2DP, USB_HEADSET, USB_DEVICE, WIRED_HEADPHONES, OTHER }

data class ClassifiedDevice(val info: AudioDeviceInfo, val kind: RouteKind) {
    val typeName: String get() = when (info.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        else -> "TYPE_${info.type}"
    }
}

class DeviceEnumerator(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun listOutputs(): List<ClassifiedDevice> =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { classify(it) }

    fun firstOf(kind: RouteKind): ClassifiedDevice? = listOutputs().firstOrNull { it.kind == kind }

    fun classify(info: AudioDeviceInfo): ClassifiedDevice {
        val k = when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> RouteKind.SPEAKER
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> RouteKind.BT_A2DP
            AudioDeviceInfo.TYPE_USB_HEADSET -> RouteKind.USB_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> RouteKind.USB_DEVICE
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> RouteKind.WIRED_HEADPHONES
            else -> RouteKind.OTHER
        }
        return ClassifiedDevice(info, k)
    }
}
