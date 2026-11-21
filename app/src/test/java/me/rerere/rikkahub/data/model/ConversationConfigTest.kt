package me.rerere.rikkahub.data.model

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationConfigTest {
    private val modelA = Model(modelId = "model-a", displayName = "Model A", tools = setOf(BuiltInTools.Search))
    private val modelB = Model(modelId = "model-b", displayName = "Model B")
    private val mcpServer = Uuid.random()
    private val workspace = Uuid.random()
    private val injection = Uuid.random()
    private val lorebook = Uuid.random()

    private val assistant = Assistant(
        chatModelId = modelA.id,
        reasoningLevel = ReasoningLevel.HIGH,
        enableWebSearch = true,
        mcpServers = setOf(mcpServer),
        workspaceId = workspace,
        enabledSkills = setOf("skill"),
        modeInjectionIds = setOf(injection),
        lorebookIds = setOf(lorebook),
    )

    private fun settings(assistant: Assistant = this.assistant) = Settings(
        assistantId = assistant.id,
        assistants = listOf(assistant),
        chatModelId = modelB.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA, modelB))),
        mcpServers = listOf(McpServerConfig.StreamableHTTPServer(id = mcpServer)),
        modeInjections = listOf(PromptInjection.ModeInjection(id = injection)),
        lorebooks = listOf(Lorebook(id = lorebook)),
    )

    private fun conversation(assistant: Assistant = this.assistant) = Conversation(
        assistantId = assistant.id,
        messageNodes = emptyList(),
    )

    @Test
    fun `unstarted conversation follows the assistant`() {
        val conversation = conversation()

        assertSame(assistant, settings().getAssistantOf(conversation))
        assertEquals(modelA, settings().getChatModelOf(conversation))
    }

    @Test
    fun `binding snapshots the assistant configuration`() {
        val bound = conversation().bindConfig(settings())

        assertEquals(
            ConversationConfig(
                chatModelId = modelA.id,
                reasoningLevel = ReasoningLevel.HIGH,
                enableWebSearch = true,
                builtInSearch = true,
                mcpServers = setOf(mcpServer),
                workspaceId = workspace,
                enabledSkills = setOf("skill"),
            ),
            bound.config,
        )
        assertEquals(setOf(injection), bound.modeInjectionIds)
        assertEquals(setOf(lorebook), bound.lorebookIds)
        assertSame(bound, bound.bindConfig(settings(assistant.copy(reasoningLevel = ReasoningLevel.OFF))))
    }

    @Test
    fun `binding resolves the global default model`() {
        val assistant = assistant.copy(chatModelId = null)

        val bound = conversation(assistant).bindConfig(settings(assistant))

        assertEquals(modelB.id, bound.config?.chatModelId)
        assertEquals(false, bound.config?.builtInSearch)
    }

    @Test
    fun `bound conversation ignores later assistant changes`() {
        val bound = conversation().bindConfig(settings())
        val changed = assistant.copy(
            chatModelId = modelB.id,
            reasoningLevel = ReasoningLevel.OFF,
            enableWebSearch = false,
            mcpServers = emptySet(),
            workspaceId = null,
            enabledSkills = emptySet(),
            modeInjectionIds = emptySet(),
            lorebookIds = emptySet(),
            systemPrompt = "changed",
        )

        val resolved = settings(changed).getAssistantOf(bound)

        assertEquals(assistant.copy(systemPrompt = "changed"), resolved)
        assertEquals(modelA, settings(changed).getChatModelOf(bound))
    }

    @Test
    fun `bound built-in search overrides the model switch`() {
        val bound = conversation().bindConfig(settings())
        val disabled = bound.copy(config = bound.config?.copy(builtInSearch = false))
        val withoutModelSearch = settings().copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA.copy(tools = emptySet()), modelB))),
        )

        assertFalse(BuiltInTools.Search in settings().getChatModelOf(disabled)!!.tools)
        assertTrue(BuiltInTools.Search in withoutModelSearch.getChatModelOf(bound)!!.tools)
    }

    @Test
    fun `bound conversation falls back to the assistant model when its model is removed`() {
        val bound = conversation().bindConfig(settings())
        val withoutModelA = settings(assistant.copy(chatModelId = null)).copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelB))),
        )

        assertEquals(modelB.id, withoutModelA.getChatModelOf(bound)?.id)
        // 会话上固定的内置搜索开关属于被删掉的模型，不能套到退回的模型上
        assertEquals(modelB, withoutModelA.getChatModelOf(bound))
    }

    @Test
    fun `binding again pins the assistant model once the pinned model is removed`() {
        val bound = conversation().bindConfig(settings())
        val withoutModelA = settings(assistant.copy(chatModelId = null)).copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelB))),
        )

        val rebound = bound.bindConfig(withoutModelA)

        assertEquals(bound.config?.copy(chatModelId = modelB.id, builtInSearch = false), rebound.config)
        assertEquals(bound.modeInjectionIds, rebound.modeInjectionIds)
        assertSame(rebound, rebound.bindConfig(withoutModelA))
    }

    @Test
    fun `bound conversation drops references to removed items`() {
        val bound = conversation().bindConfig(settings())
        val emptied = settings().copy(mcpServers = emptyList(), modeInjections = emptyList(), lorebooks = emptyList())

        val resolved = emptied.getAssistantOf(bound)

        assertEquals(emptySet<Uuid>(), resolved.mcpServers)
        assertEquals(emptySet<Uuid>(), resolved.modeInjectionIds)
        assertEquals(emptySet<Uuid>(), resolved.lorebookIds)
    }

    @Test
    fun `chat page update goes to the assistant before the conversation starts`() {
        val conversation = conversation()
        val updated = assistant.copy(reasoningLevel = ReasoningLevel.LOW)

        assertSame(conversation, conversation.withAssistantUpdate(updated, settings()))
        assertSame(updated, updated.withoutConversationFields(conversation, assistant))
    }

    @Test
    fun `chat page update stays on the started conversation`() {
        val bound = conversation().bindConfig(settings())
        val newInjection = Uuid.random()
        val updated = settings().getAssistantOf(bound).copy(
            reasoningLevel = ReasoningLevel.LOW,
            modeInjectionIds = setOf(newInjection),
            quickMessageIds = setOf(Uuid.random()),
        )

        val conversation = bound.withAssistantUpdate(updated, settings())
        val stored = updated.withoutConversationFields(bound, assistant)

        assertEquals(ReasoningLevel.LOW, conversation.config?.reasoningLevel)
        assertEquals(setOf(newInjection), conversation.modeInjectionIds)
        // 不随会话固定的字段仍然写回助手
        assertEquals(assistant.copy(quickMessageIds = updated.quickMessageIds), stored)
    }

    @Test
    fun `switching model follows the new model's built-in search`() {
        val bound = conversation().bindConfig(settings())
        val updated = settings().getAssistantOf(bound).copy(chatModelId = modelB.id)

        val conversation = bound.withAssistantUpdate(updated, settings())

        assertEquals(modelB.id, conversation.config?.chatModelId)
        assertEquals(false, conversation.config?.builtInSearch)
    }

    @Test
    fun `switching workspace resets the working directory`() {
        val bound = conversation().bindConfig(settings()).copy(workspaceCwd = "/workspace/project")
        val assistantView = settings().getAssistantOf(bound)

        val sameWorkspace = bound.withAssistantUpdate(
            assistantView.copy(reasoningLevel = ReasoningLevel.LOW),
            settings()
        )
        val otherWorkspace = bound.withAssistantUpdate(assistantView.copy(workspaceId = Uuid.random()), settings())

        assertEquals("/workspace/project", sameWorkspace.workspaceCwd)
        assertNull(otherWorkspace.workspaceCwd)
    }

    @Test
    fun `conversation bound before per-conversation config keeps its own injections`() {
        val conversationInjection = Uuid.random()
        val conversation = conversation().copy(modeInjectionIds = setOf(conversationInjection))

        val bound = conversation.bindConfig(settings())

        // 会话自己绑定过的保留，没绑定过的继承助手
        assertEquals(setOf(conversationInjection), bound.modeInjectionIds)
        assertEquals(setOf(lorebook), bound.lorebookIds)
    }

    @Test
    fun `assistant removals sync to the bound conversation keeping conversation-only picks`() {
        val bound = conversation().bindConfig(settings())
        // 会话开始后在聊天页自己勾了一个助手没有的 MCP 服务器
        val conversationOnlyMcp = Uuid.random()
        val started = bound.copy(
            config = bound.config?.copy(mcpServers = bound.config.mcpServers + conversationOnlyMcp)
        )
        val settingsWithExtra = settings().copy(
            mcpServers = settings().mcpServers + McpServerConfig.StreamableHTTPServer(id = conversationOnlyMcp),
        )
        // 助手设置里关掉学习模式注入、清空 MCP 服务器
        val changed = assistant.copy(modeInjectionIds = emptySet(), mcpServers = emptySet())

        val updated = started.withStoredAssistantDiff(assistant, changed, settingsWithExtra)

        assertEquals(emptySet<Uuid>(), updated.modeInjectionIds)
        // 助手移除的从会话上移除，会话自己勾的保留
        assertEquals(setOf(conversationOnlyMcp), updated.config?.mcpServers)
    }

    @Test
    fun `assistant additions sync to the bound conversation`() {
        val bound = conversation().bindConfig(settings())
        val newInjection = Uuid.random()
        val newSkill = "another-skill"
        val changed = assistant.copy(
            modeInjectionIds = assistant.modeInjectionIds + newInjection,
            enabledSkills = assistant.enabledSkills + newSkill,
        )
        val settingsWithExtra = settings().copy(
            modeInjections = settings().modeInjections + PromptInjection.ModeInjection(id = newInjection),
        )

        val updated = bound.withStoredAssistantDiff(assistant, changed, settingsWithExtra)

        assertEquals(setOf(injection, newInjection), updated.modeInjectionIds)
        assertEquals(setOf("skill", newSkill), updated.config?.enabledSkills)
    }

    @Test
    fun `assistant single-value changes follow with model search and cwd reset`() {
        val bound = conversation().bindConfig(settings()).copy(workspaceCwd = "/workspace/project")
        val changed = assistant.copy(chatModelId = modelB.id, workspaceId = null)

        val updated = bound.withStoredAssistantDiff(assistant, changed, settings())

        assertEquals(modelB.id, updated.config?.chatModelId)
        // 内置搜索跟随新模型自身的开关
        assertEquals(false, updated.config?.builtInSearch)
        assertNull(updated.config?.workspaceId)
        assertNull(updated.workspaceCwd)
    }

    @Test
    fun `assistant changes do not touch unstarted conversations`() {
        val unstarted = conversation()
        val changed = assistant.copy(mcpServers = emptySet(), systemPrompt = "changed")

        assertSame(unstarted, unstarted.withStoredAssistantDiff(assistant, changed, settings()))
    }

    @Test
    fun `assistant change without conversation-following fields keeps the conversation`() {
        val bound = conversation().bindConfig(settings())
        val changed = assistant.copy(systemPrompt = "changed", quickMessageIds = setOf(Uuid.random()))

        assertSame(bound, bound.withStoredAssistantDiff(assistant, changed, settings()))
    }
}
