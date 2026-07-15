package com.example.relay.permissions

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.relay.transport.NearbyPermissionGate

object NearbyPermissionPolicy {
    fun requiredRuntimePermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = when {
        sdkInt >= 33 -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        sdkInt >= 31 -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        sdkInt >= 29 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

class AndroidNearbyPermissionGate(private val context: Context) : NearbyPermissionGate {
    fun missingPermissions(): List<String> = NearbyPermissionPolicy.requiredRuntimePermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun canUseNearby(): Boolean = missingPermissions().isEmpty()
}
