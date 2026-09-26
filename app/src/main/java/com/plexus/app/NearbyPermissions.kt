package com.plexus.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat


object NearbyPermissions {


    fun requiredPermissions():
            Array<String> {


        val permissions =
            mutableListOf<String>()


        /*
         * =====================================================
         * ANDROID 12+ BLUETOOTH
         * =====================================================
         */

        permissions.add(
            Manifest.permission.BLUETOOTH_SCAN
        )

        permissions.add(
            Manifest.permission.BLUETOOTH_ADVERTISE
        )

        permissions.add(
            Manifest.permission.BLUETOOTH_CONNECT
        )


        /*
         * =====================================================
         * LOCATION COMPATIBILITY
         * =====================================================
         *
         * We are currently using
         * play-services-nearby 19.4.0.
         *
         * That version is checking both coarse and
         * fine location before allowing discovery,
         * even on our newer emulator.
         *
         * We request BOTH together.
         */

        permissions.add(
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        permissions.add(
            Manifest.permission.ACCESS_FINE_LOCATION
        )


        /*
         * =====================================================
         * ANDROID 13+
         * =====================================================
         */

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            permissions.add(
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        }


        /*
         * =====================================================
         * ANDROID 17 / API 37+
         * =====================================================
         */

        if (
            Build.VERSION.SDK_INT >= 37
        ) {

            permissions.add(
                Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        }


        return permissions
            .distinct()
            .toTypedArray()
    }


    fun hasAllPermissions(
        context: Context
    ): Boolean {


        return requiredPermissions()
            .all { permission ->


                ContextCompat
                    .checkSelfPermission(
                        context,
                        permission
                    ) ==
                        PackageManager.PERMISSION_GRANTED
            }
    }
}