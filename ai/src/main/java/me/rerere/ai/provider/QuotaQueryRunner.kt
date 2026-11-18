package me.rerere.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.util.fetchBody
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.http.getByKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.time.Instant

private val TEMPLATE_VARIABLE = Regex("""\{\{\s*(\w+)\s*\}\}""")

/** 替换模板里的 `{{name}}`，引用了不存在的变量时报错 */
fun renderQuotaTemplate(template: String, variable: (String) -> String?): String =
    TEMPLATE_VARIABLE.replace(template) { match ->
        val name = match.groupValues[1]
        variable(name) ?: throw IllegalArgumentException("Unknown variable {{$name}}")
    }

/**
 * 执行 API Key 类提供商的自定义额度查询，没有开启时返回 null。
 *
 * 先放提供商级的自定义请求头，再写入查询配置的请求头，同名时以查询配置为准。
 */
internal suspend fun OkHttpClient.runQuotaQuery(
    setting: ProviderSetting.ApiKey,
    nextApiKey: () -> String,
): ProviderQuota? = withContext(Dispatchers.IO) {
    val query = setting.quotaQuery.takeIf { it.enabled } ?: return@withContext null
    // URL、请求头和请求体引用的是同一个 key
    val apiKey by lazy(nextApiKey)
    val render = { template: String ->
        renderQuotaTemplate(template) { name ->
            when (name) {
                QuotaQuery.VARIABLE_BASE_URL -> setting.baseUrl
                QuotaQuery.VARIABLE_API_KEY -> apiKey
                else -> null
            }
        }
    }
    val request = Request.Builder()
        .url(render(query.url.trim()))
        .headers(setting.mergeCustomHeaders())
        .apply {
            query.headers
                .filter { it.name.isNotBlank() }
                .forEach { header(it.name.trim(), render(it.value)) }
        }
        .apply {
            when (query.method) {
                QuotaQueryMethod.GET -> get()
                QuotaQueryMethod.POST -> post(render(query.body).toRequestBody("application/json".toMediaType()))
            }
        }
        .build()
    val body = json.parseToJsonElement(fetchBody(request)).jsonObject
    when (query.resultKind) {
        QuotaQueryResultKind.BALANCE -> {
            val value = body.getByKey(query.resultPath)
            ProviderQuota.Balance(value.toDoubleOrNull()?.let { "%.2f".format(it) } ?: value)
        }

        QuotaQueryResultKind.USAGE_LIMITS -> parseUsageLimits(body)
    }
}

// 解析形如 OpenCode Go /v1/usage 的订阅用量响应：
// {"usage":{"rolling|weekly|monthly":{"status":"ok","percent":45,"resetsAt":"2026-11-19T05:37:32.000Z"}}}
private fun parseUsageLimits(body: JsonObject): ProviderQuota.UsageLimits {
    val usage = body["usage"]?.jsonObject ?: error("Missing usage object in response")
    var limitReached = false
    val windows = listOf(
        "rolling" to 5 * 60,
        "weekly" to 7 * 24 * 60,
        "monthly" to 30 * 24 * 60,
    ).mapNotNull { (key, windowMinutes) ->
        val window = usage[key]?.jsonObject ?: return@mapNotNull null
        val percent = window["percent"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val status = window["status"]?.jsonPrimitive?.contentOrNull
        if (percent >= 100 || (status != null && status != "ok")) {
            limitReached = true
        }
        UsageWindow(
            usedPercent = percent,
            windowMinutes = windowMinutes,
            resetsAt = window["resetsAt"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }
    if (windows.isEmpty()) {
        error("No usage windows found in response")
    }
    return ProviderQuota.UsageLimits(windows = windows, limitReached = limitReached)
}
