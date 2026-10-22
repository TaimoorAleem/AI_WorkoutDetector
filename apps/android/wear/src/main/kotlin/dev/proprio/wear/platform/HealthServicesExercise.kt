package dev.proprio.wear.platform

import android.content.Context
import androidx.concurrent.futures.await
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import dev.proprio.wear.session.ExerciseHost

/**
 * Keeps a Health Services strength-training exercise open for the duration of a session.
 * The point is not the data (none is requested: no data types, no GPS, no auto-pause) but that the OS treats the
 * app as an active workout, which Samsung's Freecess should not freeze (D-023).
 *
 * Docs: https://developer.android.com/health-and-fitness/health-services/active-data (ExerciseClient)
 */
class HealthServicesExercise(
    context: Context,
    private val log: (String) -> Unit,
    private val exerciseType: ExerciseType = ExerciseType.STRENGTH_TRAINING,
) : ExerciseHost {
    private val client: ExerciseClient = HealthServices.getClient(context.applicationContext).exerciseClient
    private var callbackSet = false

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            log("exercise state ${update.exerciseStateInfo.state}")
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            log("exercise callback registration failed: $throwable")
        }

        override fun onAvailabilityChanged(dataType: androidx.health.services.client.data.DataType<*, *>, availability: Availability) {}
    }

    override suspend fun start(): Boolean {
        return try {
            val caps = client.getCapabilitiesAsync().await()
            if (exerciseType !in caps.supportedExerciseTypes) {
                log("exercise type $exerciseType is not supported on this device")
                return false
            }
            if (!callbackSet) {
                client.setUpdateCallback(callback)
                callbackSet = true
            }
            val config = ExerciseConfig.builder(exerciseType)
                .setDataTypes(emptySet()) // minimal: we only want the workout state
                .setIsAutoPauseAndResumeEnabled(false)
                .setIsGpsEnabled(false)
                .build()
            client.startExerciseAsync(config).await()
            true
        } catch (e: SecurityException) {
            log("health services exercise denied (permission): $e")
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("health services exercise start failed: $e")
            false
        }
    }

    override suspend fun stop() {
        try {
            client.endExerciseAsync().await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing running (normal on app start) or already ended by the system.
            log("endExercise: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (callbackSet) {
            runCatching { client.clearUpdateCallbackAsync(callback).await() }
            callbackSet = false
        }
    }
}
