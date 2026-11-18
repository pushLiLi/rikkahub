package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNormalizationTest {
    private val builtIn = DEFAULT_PROVIDERS.first()

    @Test
    fun `hidden built-in providers are not resurrected`() {
        val settings = Settings(
            providers = DEFAULT_PROVIDERS.filter { it.id != builtIn.id },
            hiddenProviderIds = listOf(builtIn.id),
        )

        val normalized = settings.normalized()

        assertTrue(normalized.providers.none { it.id == builtIn.id })
    }

    @Test
    fun `empty provider list stays empty when all built-ins are hidden`() {
        val settings = Settings(
            providers = emptyList(),
            hiddenProviderIds = DEFAULT_PROVIDERS.map { it.id },
        )

        assertTrue(settings.normalized().providers.isEmpty())
    }

    @Test
    fun `clearing hidden ids restores built-in providers`() {
        val hidden = Settings(
            providers = DEFAULT_PROVIDERS.filter { it.id != builtIn.id },
            hiddenProviderIds = listOf(builtIn.id),
        ).normalized()

        val restored = hidden.copy(hiddenProviderIds = emptyList()).normalized()

        assertTrue(restored.providers.any { it.id == builtIn.id })
    }

    @Test
    fun `hidden built-in re-added explicitly is kept`() {
        val settings = Settings(
            providers = listOf(builtIn),
            hiddenProviderIds = listOf(builtIn.id),
        )

        val normalized = settings.normalized()

        assertEquals(1, normalized.providers.count { it.id == builtIn.id })
    }

    private val defaultAssistant = DEFAULT_ASSISTANTS.first()

    @Test
    fun `hidden default assistants are not resurrected`() {
        val settings = Settings(
            assistants = DEFAULT_ASSISTANTS.filter { it.id != defaultAssistant.id },
            hiddenAssistantIds = listOf(defaultAssistant.id),
        )

        val normalized = settings.normalized()

        assertTrue(normalized.assistants.none { it.id == defaultAssistant.id })
    }

    @Test
    fun `empty assistant list stays empty when all defaults are hidden`() {
        val settings = Settings(
            assistants = emptyList(),
            hiddenAssistantIds = DEFAULT_ASSISTANTS.map { it.id },
        )

        assertTrue(settings.normalized().assistants.isEmpty())
    }

    @Test
    fun `clearing hidden assistant ids restores defaults`() {
        val hidden = Settings(
            assistants = DEFAULT_ASSISTANTS.filter { it.id != defaultAssistant.id },
            hiddenAssistantIds = listOf(defaultAssistant.id),
        ).normalized()

        val restored = hidden.copy(hiddenAssistantIds = emptyList()).normalized()

        assertTrue(restored.assistants.any { it.id == defaultAssistant.id })
    }
}
