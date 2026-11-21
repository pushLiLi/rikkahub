package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ModelsDevCatalog
import me.rerere.ai.registry.ProviderPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderPresetsTest {
    private val fixture = """
        {
          "deepseek": {
            "name": "DeepSeek",
            "api": "https://api.deepseek.com",
            "npm": "@ai-sdk/openai-compatible",
            "models": {
              "deepseek-v4-pro": {
                "name": "DeepSeek V4 Pro",
                "reasoning": true,
                "tool_call": true,
                "limit": { "context": 1048576 }
              },
              "deepseek-old": {
                "status": "deprecated",
                "tool_call": true
              }
            }
          },
          "anthropic": {
            "name": "Anthropic",
            "npm": "@ai-sdk/anthropic",
            "models": { "claude-sonnet-5": { "name": "Claude Sonnet 5" } }
          },
          "google": {
            "name": "Google",
            "npm": "@ai-sdk/google",
            "models": { "gemini-4": {} }
          },
          "groq": {
            "name": "Groq",
            "npm": "@ai-sdk/groq",
            "models": { "llama-4": {} }
          },
          "no-api-unknown": {
            "name": "Mystery",
            "npm": "@ai-sdk/openai-compatible",
            "models": { "mystery-1": {} }
          }
        }
    """.trimIndent()

    @Before
    fun installCatalog() {
        ModelRegistry.installCatalog(ModelsDevCatalog.decode(fixture.byteInputStream()))
    }

    @After
    fun uninstallCatalog() {
        ModelRegistry.installCatalog(null)
    }

    private fun preset(id: String): ProviderPreset =
        usableProviderPresets().first { it.id == id }

    @Test
    fun directoryOnlyKeepsConnectablePresets() {
        val ids = usableProviderPresets().map { it.id }
        assertTrue("deepseek" in ids)
        assertTrue("anthropic" in ids)
        assertTrue("google" in ids)
        assertTrue("groq" in ids)
        // 没有 api 地址又不在兜底表里的供应商不进目录
        assertFalse("no-api-unknown" in ids)
    }

    @Test
    fun protocolMapsToProviderType() {
        assertTrue(preset("deepseek").let { createProviderFromPreset(it) } is ProviderSetting.OpenAI)
        assertEquals(
            "https://api.deepseek.com",
            (createProviderFromPreset(preset("deepseek")) as ProviderSetting.OpenAI).baseUrl,
        )
        assertTrue(createProviderFromPreset(preset("anthropic")) is ProviderSetting.Claude)
        assertTrue(createProviderFromPreset(preset("google")) is ProviderSetting.Google)
    }

    @Test
    fun fallbackBaseUrlForNativeProviders() {
        val groq = createProviderFromPreset(preset("groq")) as ProviderSetting.OpenAI
        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl)
    }

    @Test
    fun prefillModelsSkipDeprecatedAndCarryAbilities() {
        val deepseek = createProviderFromPreset(preset("deepseek")) as ProviderSetting.OpenAI
        assertEquals(listOf("deepseek-v4-pro"), deepseek.models.map { it.modelId })
        val model = deepseek.models.single()
        assertEquals("DeepSeek V4 Pro", model.displayName)
        assertEquals(1_048_576, model.contextLength)
        assertTrue(ModelAbility.TOOL in model.abilities)
        assertTrue(ModelAbility.REASONING in model.abilities)

        // 目录里没给上下文长度时回落到默认值
        val anthropic = createProviderFromPreset(preset("anthropic")) as ProviderSetting.Claude
        assertEquals(1_000_000, anthropic.models.single().contextLength)
    }

    @Test
    fun addedDetectionIsByName() {
        val preset = preset("deepseek")
        val existing = ProviderSetting.OpenAI(name = "deepseek", baseUrl = "https://api.deepseek.com")
        assertTrue(preset.isAdded(listOf(existing)))
        assertFalse(preset.isAdded(emptyList()))
    }
}
