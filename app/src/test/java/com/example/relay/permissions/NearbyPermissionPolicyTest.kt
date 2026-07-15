package com.example.relay.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPermissionPolicyTest {
    @Test
    fun `api 23 through 28 require coarse location`() {
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), NearbyPermissionPolicy.requiredRuntimePermissions(28))
    }

    @Test
    fun `api 29 and 30 require fine location`() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), NearbyPermissionPolicy.requiredRuntimePermissions(30))
    }

    @Test
    fun `api 31 and 32 require bluetooth runtime permissions`() {
        assertEquals(
            setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
            NearbyPermissionPolicy.requiredRuntimePermissions(32).toSet(),
        )
    }

    @Test
    fun `api 33 adds nearby wifi and notification permission`() {
        val permissions = NearbyPermissionPolicy.requiredRuntimePermissions(33)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
    }
}
