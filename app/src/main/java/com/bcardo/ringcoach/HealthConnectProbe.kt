package com.bcardo.ringcoach

import androidx.annotation.StringRes
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

internal const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
internal const val PROBE_DAYS = 7L

internal enum class ProbeStatus(@field:StringRes val label: Int) {
    AVAILABLE(R.string.status_available),
    UNSUPPORTED(R.string.status_unsupported),
    NO_PERMISSION(R.string.status_no_permission),
    EMPTY(R.string.status_empty),
    ERROR(R.string.status_error),
}

internal data class ProbeSource(val packageName: String, val device: Device?)

internal data class ProbeCoverage(
    val records: Int,
    val samples: Int,
    val earliest: Instant,
    val latest: Instant,
    val sources: Map<ProbeSource, Int>,
)

internal data class MetricResult(
    val kind: MetricKind,
    @field:StringRes val samplesLabel: Int?,
    val status: ProbeStatus,
    val coverage: ProbeCoverage? = null,
    val exportRecords: List<JSONObject> = emptyList(),
)

internal class HealthConnectProbe(private val client: HealthConnectClient) {
    private class Metric<T : Record>(
        val kind: MetricKind,
        val type: KClass<T>,
        val bounds: (T) -> Pair<Instant, Instant>,
        val encode: (T) -> JSONObject,
        @field:StringRes val samplesLabel: Int? = null,
        val sampleCount: (T) -> Int = { 0 },
        val feature: Int? = null,
    ) {
        val permission = HealthPermission.getReadPermission(type)

        fun isSupported(client: HealthConnectClient): Boolean = feature == null ||
            client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

        suspend fun read(
            client: HealthConnectClient,
            granted: Set<String>,
            range: TimeRangeFilter,
            exportWindow: ExportWindow? = null,
        ): MetricResult {
            if (!isSupported(client)) return MetricResult(kind, samplesLabel, ProbeStatus.UNSUPPORTED)
            if (permission !in granted) return MetricResult(kind, samplesLabel, ProbeStatus.NO_PERMISSION)

            return try {
                var count = 0
                var samples = 0
                var earliest = Instant.MAX
                var latest = Instant.MIN
                val sources = linkedMapOf<ProbeSource, Int>()
                val exportRecords = mutableListOf<JSONObject>()
                var pageToken: String? = null
                do {
                    val page = client.readRecords(ReadRecordsRequest(type, range, pageToken = pageToken))
                    for (record in page.records) {
                        val (start, end) = bounds(record)
                        if (exportWindow != null) {
                            if (start < exportWindow.start || start >= exportWindow.end || end > exportWindow.end) continue
                            exportRecords += encode(record)
                        }
                        earliest = minOf(earliest, start)
                        latest = maxOf(latest, end)
                        count++
                        samples += sampleCount(record)
                        val source = ProbeSource(record.metadata.dataOrigin.packageName, record.metadata.device)
                        sources[source] = (sources[source] ?: 0) + 1
                    }
                    pageToken = page.pageToken
                } while (pageToken != null)

                if (count == 0) {
                    MetricResult(kind, samplesLabel, ProbeStatus.EMPTY)
                } else {
                    val coverage = ProbeCoverage(count, samples, earliest, latest, sources)
                    MetricResult(kind, samplesLabel, ProbeStatus.AVAILABLE, coverage, exportRecords)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: SecurityException) {
                MetricResult(kind, samplesLabel, ProbeStatus.NO_PERMISSION)
            } catch (_: Exception) {
                MetricResult(kind, samplesLabel, ProbeStatus.ERROR)
            }
        }
    }

    private val metrics = listOf(
        Metric(
            MetricKind.SLEEP, SleepSessionRecord::class,
            bounds = { it.startTime to it.endTime },
            encode = TelemetryExport::sleep,
            samplesLabel = R.string.sleep_stages_count,
            sampleCount = { it.stages.size },
        ),
        Metric(
            MetricKind.HEART_RATE, HeartRateRecord::class,
            bounds = { it.startTime to it.endTime },
            encode = TelemetryExport::heartRate,
            samplesLabel = R.string.heart_rate_samples_count,
            sampleCount = { it.samples.size },
        ),
        Metric(
            MetricKind.HRV, HeartRateVariabilityRmssdRecord::class,
            bounds = { it.time to it.time },
            encode = TelemetryExport::hrv,
        ),
        Metric(
            MetricKind.SKIN_TEMPERATURE, SkinTemperatureRecord::class,
            bounds = { it.startTime to it.endTime },
            encode = TelemetryExport::skinTemperature,
            samplesLabel = R.string.temperature_deltas_count,
            sampleCount = { it.deltas.size },
            feature = HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE,
        ),
    )

    suspend fun missingPermissions(): Set<String> {
        val granted = client.permissionController.getGrantedPermissions()
        return metrics.filter { it.isSupported(client) }.map { it.permission }.toSet() - granted
    }

    suspend fun read(): List<MetricResult> {
        val window = recentWindow()
        val range = TimeRangeFilter.between(window.start, window.end)
        val granted = client.permissionController.getGrantedPermissions()
        return metrics.map { it.read(client, granted, range) }
    }

    suspend fun prepareExport(selected: Set<MetricKind>): String? {
        val window = recentWindow()
        val range = TimeRangeFilter.between(window.start, window.end)
        val granted = client.permissionController.getGrantedPermissions()
        val results = metrics.filter { it.kind in selected }.map { it.read(client, granted, range, window) }
        if (results.none { it.exportRecords.isNotEmpty() }) return null
        return TelemetryExport.encode(window, results)
    }

    suspend fun coachingSnapshot(date: LocalDate = LocalDate.now()): JSONObject {
        val window = recentWindow(minOf(Instant.now(), date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()))
        val range = TimeRangeFilter.between(window.start, window.end)
        val granted = client.permissionController.getGrantedPermissions()
        val results = metrics.map { it.read(client, granted, range, window) }
        return CoachingTelemetry.summarize(window, results, ZoneId.systemDefault())
    }

    private fun recentWindow(end: Instant = Instant.now()): ExportWindow {
        return ExportWindow(end.minus(Duration.ofDays(PROBE_DAYS)), end)
    }
}
