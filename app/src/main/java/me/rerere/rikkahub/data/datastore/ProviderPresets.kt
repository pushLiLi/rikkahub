package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ProviderPreset

/** 从目录添加供应商时预填的模型数量上限 */
const val PROVIDER_PRESET_MODEL_LIMIT = 20

/**
 * models.dev 没给 api 地址的原生大厂（它们有自己的专属 SDK，目录只标协议），
 * 接入地址固定，这里兜底。
 */
private val FALLBACK_BASE_URLS = mapOf(
    "openai" to "https://api.openai.com/v1",
    "anthropic" to "https://api.anthropic.com/v1",
    "google" to "https://generativelanguage.googleapis.com/v1beta",
    "xai" to "https://api.x.ai/v1",
    "groq" to "https://api.groq.com/openai/v1",
    "togetherai" to "https://api.together.xyz/v1",
    "cerebras" to "https://api.cerebras.ai/v1",
    "mistral" to "https://api.mistral.ai/v1",
    "perplexity" to "https://api.perplexity.ai",
)

/** 目录里优先展示的热门供应商（models.dev 的 id） */
private val PINNED_PRESET_IDS = listOf(
    "deepseek", "openai", "anthropic", "google", "openrouter",
    "moonshotai-cn", "siliconflow-cn", "zhipuai", "alibaba-cn", "groq", "minimax", "stepfun",
)

private val pinnedRank = PINNED_PRESET_IDS.withIndex().associate { (index, id) -> id to index }

/** 可接入的预设目录：能解析出 baseUrl 且有聊天模型，热门供应商排前面 */
fun usableProviderPresets(): List<ProviderPreset> =
    ModelRegistry.providerPresets
        .filter { preset -> presetBaseUrl(preset) != null }
        .sortedWith(compareBy({ pinnedRank[it.id] ?: Int.MAX_VALUE }, { it.displayName.lowercase() }))

fun presetBaseUrl(preset: ProviderPreset): String? =
    preset.baseUrl ?: FALLBACK_BASE_URLS[preset.id]

/** 预设是否已添加过（目录项没有固定 id，按显示名去重） */
fun ProviderPreset.isAdded(providers: List<ProviderSetting>): Boolean =
    providers.any { it.name.equals(displayName, ignoreCase = true) }

/**
 * 把目录预设转成可保存的 [ProviderSetting]：
 * 协议按 models.dev 的 npm 标注映射（Anthropic 协议 → Claude 类型，Google 协议 → Google 类型，
 * 其余一律 OpenAI 兼容类型），并预填该家的聊天模型（能力、上下文长度来自目录）。
 */
fun createProviderFromPreset(preset: ProviderPreset): ProviderSetting {
    val baseUrl = presetBaseUrl(preset)
    val models = ModelRegistry.presetModelsOf(preset.id, PROVIDER_PRESET_MODEL_LIMIT).map { presetModel ->
        Model(
            modelId = presetModel.modelId,
            displayName = presetModel.displayName,
            inputModalities = presetModel.info.inputModalities.toList(),
            outputModalities = presetModel.info.outputModalities.toList(),
            abilities = presetModel.info.abilities.toList(),
            contextLength = presetModel.info.contextLength ?: 1_000_000,
        )
    }
    return when (preset.npmPackage) {
        "@ai-sdk/anthropic" -> ProviderSetting.Claude(
            name = preset.displayName,
            baseUrl = baseUrl ?: "https://api.anthropic.com/v1",
            models = models,
        )

        "@ai-sdk/google" -> ProviderSetting.Google(
            name = preset.displayName,
            baseUrl = baseUrl ?: "https://generativelanguage.googleapis.com/v1beta",
            models = models,
        )

        else -> ProviderSetting.OpenAI(
            name = preset.displayName,
            baseUrl = baseUrl ?: "https://api.openai.com/v1",
            models = models,
        )
    }
}
