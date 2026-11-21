package me.rerere.ai.registry

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import java.io.InputStream
import java.io.OutputStream

class ModelInfo(
    val inputModalities: Set<Modality>,
    val outputModalities: Set<Modality>,
    val abilities: Set<ModelAbility>,
    val contextLength: Int?,
)

/** 目录供应商预设：models.dev 里一家可接入的 API 提供商 */
class ProviderPreset(
    val id: String,
    val displayName: String,
    /** 接入地址（api 字段），原生大厂可能为 null，由调用方兜底 */
    val baseUrl: String?,
    val docUrl: String?,
    /** models.dev 标注的协议 SDK 包名，如 @ai-sdk/openai-compatible / @ai-sdk/anthropic */
    val npmPackage: String?,
    val chatModelCount: Int,
)

/** 供应商预设里的单个聊天模型 */
class PresetModel(
    val modelId: String,
    val displayName: String,
    val info: ModelInfo,
)

/**
 * models.dev (https://models.dev/api.json) 的模型数据。
 *
 * 同一个模型会出现在多个提供商下，id 写法也各不相同（如 `claude-sonnet-4-5`、`anthropic/claude-sonnet-4.5`），
 * 这里把每条记录按完整 id、去掉前缀后的名称、canonical id 建索引，同一个 key 下的多条记录按多数投票合并。
 */
class ModelsDevCatalog private constructor(
    private val providers: Map<String, ModelsDevProvider>
) {
    private val index: Map<String, ModelInfo> = buildIndex(providers)

    val size: Int get() = index.size

    fun find(modelId: String): ModelInfo? {
        val id = modelId.trim().lowercase()
        if (id.isEmpty()) return null
        val name = id.substringAfterLast('/')
        // OpenRouter 等用 `:free`、`:thinking` 之类的后缀区分同一模型的不同渠道
        return index[id]
            ?: index[id.substringBefore(':')]
            ?: index[name]
            ?: index[name.substringBefore(':')]
    }

    /** 写出精简后的 JSON（模型匹配 + 供应商目录所需字段），格式仍是 models.dev api.json 的子集，可再用 [decode] 读回 */
    @OptIn(ExperimentalSerializationApi::class)
    fun encodeTo(output: OutputStream) {
        json.encodeToStream(providers, output)
    }

    /** 供应商预设目录，只列有可用聊天模型的提供商 */
    fun providerPresets(): List<ProviderPreset> =
        providers.mapNotNull { (id, provider) ->
            val chatModelCount = provider.models.values.count(::isUsableChatModel)
            if (chatModelCount == 0) return@mapNotNull null
            ProviderPreset(
                id = id,
                displayName = provider.name?.takeIf { it.isNotBlank() } ?: id,
                baseUrl = provider.api?.takeIf { it.isNotBlank() },
                docUrl = provider.doc?.takeIf { it.isNotBlank() },
                npmPackage = provider.npm?.takeIf { it.isNotBlank() },
                chatModelCount = chatModelCount,
            )
        }

    /** 该供应商可预填的聊天模型：非 deprecated、输出含文本，最多 [limit] 个 */
    fun chatModelsOf(providerId: String, limit: Int): List<PresetModel> {
        val provider = providers[providerId] ?: return emptyList()
        return provider.models.entries
            .filter { (_, model) -> isUsableChatModel(model) }
            .take(limit)
            .map { (modelId, model) ->
                PresetModel(
                    modelId = modelId,
                    displayName = model.name?.takeIf { it.isNotBlank() } ?: modelId,
                    info = model.toModelInfo(),
                )
            }
    }

    private fun isUsableChatModel(model: ModelsDevModel): Boolean =
        model.status != "deprecated" && "text" in model.modalities.output

    private fun ModelsDevModel.toModelInfo(): ModelInfo = ModelInfo(
        inputModalities = modalities.input.toModalitySet(),
        outputModalities = modalities.output.toModalitySet(),
        abilities = buildSet {
            if (toolCall) add(ModelAbility.TOOL)
            if (reasoning) add(ModelAbility.REASONING)
        },
        contextLength = limit?.context?.takeIf { it > 0 },
    )

    private fun List<String>.toModalitySet(): Set<Modality> =
        mapNotNull { name -> Modality.entries.find { it.name.equals(name, ignoreCase = true) } }
            .toSet()
            .ifEmpty { setOf(Modality.TEXT) }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun decode(input: InputStream): ModelsDevCatalog {
            val providers = json.decodeFromStream<Map<String, ModelsDevProvider>>(input)
            return ModelsDevCatalog(providers.mapValues { (_, provider) -> provider.slim() })
        }
    }
}

private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
private data class ModelsDevProvider(
    val name: String? = null,
    val api: String? = null,
    val doc: String? = null,
    val npm: String? = null,
    val models: Map<String, ModelsDevModel> = emptyMap()
) {
    fun slim() = copy(models = models.mapValues { (_, model) -> model.slim() })
}

@Serializable
private data class ModelsDevModel(
    val name: String? = null,
    val status: String? = null,
    val modalities: ModelsDevModalities = ModelsDevModalities(),
    @SerialName("tool_call")
    val toolCall: Boolean = false,
    val reasoning: Boolean = false,
    val limit: ModelsDevLimit? = null,
    @SerialName("canonical_model_id")
    val canonicalModelId: String? = null,
) {
    fun slim() = copy(
        modalities = ModelsDevModalities(
            input = modalities.input.filter { it in USED_MODALITIES },
            output = modalities.output.filter { it in USED_MODALITIES },
        ),
        limit = limit?.takeIf { it.context != null },
    )
}

@Serializable
private data class ModelsDevModalities(
    val input: List<String> = listOf("text"),
    val output: List<String> = listOf("text"),
)

@Serializable
private data class ModelsDevLimit(
    val context: Int? = null,
)

private val USED_MODALITIES = setOf("text", "image")

private class Votes {
    var total = 0
    var imageInput = 0
    var imageOutput = 0
    var tool = 0
    var reasoning = 0
    val contexts = HashMap<Int, Int>()

    fun add(model: ModelsDevModel) {
        total += 1
        if ("image" in model.modalities.input) imageInput += 1
        if ("image" in model.modalities.output) imageOutput += 1
        if (model.toolCall) tool += 1
        if (model.reasoning) reasoning += 1
        model.limit?.context?.takeIf { it > 0 }?.let { contexts.merge(it, 1, Int::plus) }
    }

    // 各家数据出错多是漏标能力，所以票数持平时算支持
    private fun majority(count: Int) = count * 2 >= total

    fun toModelInfo() = ModelInfo(
        inputModalities = if (majority(imageInput)) setOf(Modality.TEXT, Modality.IMAGE) else setOf(Modality.TEXT),
        outputModalities = if (majority(imageOutput)) setOf(Modality.TEXT, Modality.IMAGE) else setOf(Modality.TEXT),
        abilities = buildSet {
            if (majority(tool)) add(ModelAbility.TOOL)
            if (majority(reasoning)) add(ModelAbility.REASONING)
        },
        contextLength = contexts.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key,
    )
}

private fun buildIndex(providers: Map<String, ModelsDevProvider>): Map<String, ModelInfo> {
    val votes = HashMap<String, Votes>()
    for (provider in providers.values) {
        for ((id, model) in provider.models) {
            val keys = buildSet {
                val lower = id.lowercase()
                add(lower)
                add(lower.substringAfterLast('/'))
                model.canonicalModelId?.lowercase()?.let {
                    add(it)
                    add(it.substringAfterLast('/'))
                }
            }
            for (key in keys) {
                if (key.isNotEmpty()) votes.getOrPut(key, ::Votes).add(model)
            }
        }
    }
    return votes.mapValues { (_, it) -> it.toModelInfo() }
}
