package com.miniseuleros.app.data.db

import com.miniseuleros.app.data.model.LLMModel
import com.miniseuleros.app.data.model.ModelEntry
import com.miniseuleros.app.data.model.ModelGroup
import com.miniseuleros.app.data.model.ProviderConfig
import com.miniseuleros.app.data.model.ProviderCredential
import com.miniseuleros.app.data.model.ProviderInstance
import com.miniseuleros.app.data.model.ProviderType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-subagent-meta-mapping] Regression guard for the 2026-09-26
 * field-test bug: `compactSummaryGroupId` and `subAgentGroupId` existed on
 * [ProviderConfig] and their setters/saveConfig ran fine, but they were
 * missing from BOTH directions of the DB meta mapping — so every tap on the
 * 压缩摘要 / 子代理 dropdowns snapped back to 无 (canonicalized config came
 * back without the field), while Default Primary/Sub, which WERE mapped,
 * worked. Any future group-pointer field added to ProviderConfig must be
 * added to ProviderConfigMetaKeys + toSnapshot + toProviderConfig — this
 * round-trip test fails if a pointer is dropped in either direction.
 */
class ProviderConfigGroupPointerRoundTripTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private fun seededConfig(): ProviderConfig {
        val instance = ProviderInstance(
            id = "inst1",
            label = "Test Provider",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
        )
        val entry = ModelEntry(
            providerInstanceId = "inst1",
            baseModel = LLMModel(id = "m1", displayName = "Model One", provider = "inst1"),
        )
        val group = ModelGroup(name = "Pool", memberEntryIds = mutableListOf(entry.uuid))
        return ProviderConfig(
            instances = mutableListOf(instance),
            modelEntries = mutableListOf(entry),
            modelGroups = mutableListOf(group),
            defaultPrimaryGroupId = group.id,
            defaultSubGroupId = group.id,
            voiceInputGroupId = group.id,
            voiceOutputGroupId = group.id,
            visionGroupId = group.id,
            compactSummaryGroupId = group.id,
            subAgentGroupId = group.id,
        )
    }

    @Test
    fun `every group pointer survives toSnapshot then toProviderConfig`() {
        val config = seededConfig()

        // Round-trip with a null jsonSyncHash, exactly as the save path's
        // canonicalization step does before emitting the new config.
        val snapshot = config.toSnapshot(jsonForBlobs = json)
        val back = snapshot.toProviderConfig(jsonForBlobs = json)

        assertEquals(config.defaultPrimaryGroupId, back.defaultPrimaryGroupId)
        assertEquals(config.defaultSubGroupId, back.defaultSubGroupId)
        assertEquals(config.voiceInputGroupId, back.voiceInputGroupId)
        assertEquals(config.voiceOutputGroupId, back.voiceOutputGroupId)
        assertEquals(config.visionGroupId, back.visionGroupId)
        // The two fields that were silently dropped before the fix:
        assertEquals(config.compactSummaryGroupId, back.compactSummaryGroupId)
        assertEquals(config.subAgentGroupId, back.subAgentGroupId)
    }

    @Test
    fun `unset pointers stay unset after round trip`() {
        val config = seededConfig().copy(
            compactSummaryGroupId = null,
            subAgentGroupId = null,
            visionGroupId = null,
        )
        val snapshot = config.toSnapshot(jsonForBlobs = json)
        val back = snapshot.toProviderConfig(jsonForBlobs = json)
        assertEquals(null, back.compactSummaryGroupId)
        assertEquals(null, back.subAgentGroupId)
        assertEquals(null, back.visionGroupId)
    }

    @Test
    fun `snapshot carries one meta row per bound pointer`() {
        val snapshot = seededConfig().toSnapshot(jsonForBlobs = json)
        val keys = snapshot.meta.map { it.key }.toSet()
        assertEquals(
            setOf(
                ProviderConfigMetaKeys.DEFAULT_PRIMARY_GROUP_ID,
                ProviderConfigMetaKeys.DEFAULT_SUB_GROUP_ID,
                ProviderConfigMetaKeys.VOICE_INPUT_GROUP_ID,
                ProviderConfigMetaKeys.VOICE_OUTPUT_GROUP_ID,
                ProviderConfigMetaKeys.VISION_GROUP_ID,
                ProviderConfigMetaKeys.COMPACT_SUMMARY_GROUP_ID,
                ProviderConfigMetaKeys.SUB_AGENT_GROUP_ID,
            ),
            keys,
        )
    }
}
