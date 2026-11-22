package io.max.paperplay.core.eq

import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.player.ParametricEqCodec
import io.max.paperplay.player.ParametricEqProfile

data class DeviceEqAssignment(
    val deviceKey: String,
    val deviceLabel: String,
    val presetId: String?,
    val enabled: Boolean,
    val profile: ParametricEqProfile,
    val updatedAt: Long,
)

/** SQLDelight persistence for user 31-band parametric EQ presets and per-device auto-assignment. */
class EqRepository(
    private val db: LibraryDatabase,
    private val clock: () -> Long,
) {
    private val q get() = db.eqQueries

    fun allPresets(): List<ParametricEqProfile> = q.allEqPresets().executeAsList().mapNotNull { row ->
        val parsed = ParametricEqCodec.parsePowerampJson(row.bands_json)
        parsed?.copy(
            id = row.id,
            name = row.name,
            source = row.source,
            preampDb = row.preamp_db.toFloat(),
            autoPreamp = row.auto_preamp != 0L,
            limiterEnabled = row.limiter_enabled != 0L,
        )
    }

    fun savePreset(profile: ParametricEqProfile) {
        val json = ParametricEqCodec.exportPowerampJson(profile, includePaperPlayMeta = true)
        q.upsertEqPreset(
            id = profile.id,
            name = profile.name,
            source = profile.source,
            preamp_db = profile.preampDb.toDouble(),
            auto_preamp = if (profile.autoPreamp) 1L else 0L,
            limiter_enabled = if (profile.limiterEnabled) 1L else 0L,
            bands_json = json,
            updated_at = clock(),
        )
    }

    fun deletePreset(id: String) {
        q.deleteEqPreset(id)
    }

    fun allDeviceAssignments(): List<DeviceEqAssignment> = q.allEqDeviceMaps().executeAsList().mapNotNull { row ->
        val profile = ParametricEqCodec.parsePowerampJson(row.profile_json) ?: return@mapNotNull null
        DeviceEqAssignment(
            deviceKey = row.device_key,
            deviceLabel = row.device_label,
            presetId = row.preset_id,
            enabled = row.enabled != 0L,
            profile = profile,
            updatedAt = row.updated_at,
        )
    }

    fun assignmentForDevice(deviceKey: String): DeviceEqAssignment? {
        val row = q.eqDeviceMapByKey(deviceKey).executeAsOneOrNull() ?: return null
        val profile = ParametricEqCodec.parsePowerampJson(row.profile_json) ?: return null
        return DeviceEqAssignment(
            deviceKey = row.device_key,
            deviceLabel = row.device_label,
            presetId = row.preset_id,
            enabled = row.enabled != 0L,
            profile = profile,
            updatedAt = row.updated_at,
        )
    }

    fun assignToDevice(
        deviceKey: String,
        deviceLabel: String,
        profile: ParametricEqProfile,
        enabled: Boolean,
    ) {
        val json = ParametricEqCodec.exportPowerampJson(profile, includePaperPlayMeta = true)
        q.upsertEqDeviceMap(
            device_key = deviceKey,
            device_label = deviceLabel,
            preset_id = profile.id,
            enabled = if (enabled) 1L else 0L,
            profile_json = json,
            updated_at = clock(),
        )
    }

    fun unassignDevice(deviceKey: String) {
        q.deleteEqDeviceMap(deviceKey)
    }
}
