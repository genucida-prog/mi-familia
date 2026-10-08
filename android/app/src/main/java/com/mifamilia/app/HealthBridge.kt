package com.mifamilia.app

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads today's step count from Health Connect on behalf of the page
 * (`NexoNative.healthSteps` / `NexoNative.healthRequest`).
 *
 * Everything degrades to the string "na": no Health Connect provider on the
 * device, permission not granted, or any error. `request()` opens the Health
 * Connect permission screen and reports the outcome through [onResult], which
 * [MainActivity.NexoBridge] forwards to `window.nexoHealthPerm(granted)` in
 * the WebView.
 */
object HealthBridge {

    // Misma cadena que el <uses-permission> del manifest (const pública de HC).
    private const val PERM_READ_STEPS = "android.permission.health.READ_STEPS"

    private var launcher: ActivityResultLauncher<Set<String>>? = null
    private var pending: ((Boolean) -> Unit)? = null

    private fun client(activity: ComponentActivity): HealthConnectClient? {
        if (Build.VERSION.SDK_INT < 26) return null
        return try {
            if (HealthConnectClient.getSdkStatus(activity) != HealthConnectClient.SDK_AVAILABLE) null
            else HealthConnectClient.getOrCreate(activity)
        } catch (e: Exception) {
            null
        }
    }

    /** Steps walked today as a decimal string, or "na" when unavailable. */
    fun steps(activity: ComponentActivity): String {
        val hc = client(activity) ?: return "na"
        return try {
            runBlocking {
                val granted = hc.permissionController.getGrantedPermissions()
                if (PERM_READ_STEPS !in granted) return@runBlocking "na"
                val zone = ZoneId.systemDefault()
                val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
                val resp = hc.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(start, Instant.now())
                    )
                )
                (resp[StepsRecord.COUNT_TOTAL] ?: 0L).toString()
            }
        } catch (e: Exception) {
            "na"
        }
    }

    /** Asks for READ_STEPS and reports whether it ended granted. */
    fun request(activity: ComponentActivity, onResult: (Boolean) -> Unit) {
        activity.runOnUiThread {
            try {
                if (client(activity) == null) {
                    onResult(false)
                    return@runOnUiThread
                }
                val contract = PermissionController.createRequestPermissionResultContract()
                if (launcher == null) {
                    launcher = activity.activityResultRegistry.register<Set<String>, Set<String>>(
                        "nexo-health-perm",
                        contract
                    ) { granted ->
                        val cb = pending
                        pending = null
                        cb?.invoke(PERM_READ_STEPS in granted)
                    }
                }
                pending = onResult
                val l = launcher
                if (l == null) {
                    pending = null
                    onResult(false)
                } else {
                    l.launch(setOf(PERM_READ_STEPS))
                }
            } catch (e: Exception) {
                pending = null
                onResult(false)
            }
        }
    }
}