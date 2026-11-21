package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import kotlin.uuid.Uuid

/**
 * 会话开始后固定在会话上的配置。
 *
 * 新会话没有这份配置，聊天页里的切换直接改助手；会话落库时从助手拍一份快照，
 * 之后的切换只影响当前会话，别处的改动不会打乱它的上下文和缓存。
 * 模式注入和 Lorebook 同样随会话固定，沿用 [Conversation.modeInjectionIds] 与 [Conversation.lorebookIds]。
 */
@Serializable
data class ConversationConfig(
    val chatModelId: Uuid? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val enableWebSearch: Boolean = false,
    val builtInSearch: Boolean = false, // 模型内置搜索，覆盖模型自身的开关
    val mcpServers: Set<Uuid> = emptySet(),
    val workspaceId: Uuid? = null,
    val workspaceAgentMode: WorkspaceAgentMode = WorkspaceAgentMode.BUILD,
    val enabledSkills: Set<String> = emptySet(),
)

// 内置搜索是模型的开关，不在助手上，由调用方另行填入
private fun Assistant.toConversationConfig() = ConversationConfig(
    chatModelId = chatModelId,
    reasoningLevel = reasoningLevel,
    enableWebSearch = enableWebSearch,
    mcpServers = mcpServers,
    workspaceId = workspaceId,
    workspaceAgentMode = workspaceAgentMode,
    enabledSkills = enabledSkills,
)

private fun Assistant.withConversationConfig(config: ConversationConfig) = copy(
    chatModelId = config.chatModelId,
    reasoningLevel = config.reasoningLevel,
    enableWebSearch = config.enableWebSearch,
    mcpServers = config.mcpServers,
    workspaceId = config.workspaceId,
    workspaceAgentMode = config.workspaceAgentMode,
    enabledSkills = config.enabledSkills,
)

/** 会话视角下的助手：会话开始后，会话持有的字段覆盖助手自身的值。 */
fun Assistant.withConversation(conversation: Conversation): Assistant {
    val config = conversation.config ?: return this
    return withConversationConfig(config).copy(
        modeInjectionIds = conversation.modeInjectionIds,
        lorebookIds = conversation.lorebookIds,
    )
}

/** 聊天页改动助手后留在会话上的部分，[updated] 是会话视角下改动后的助手。 */
fun Conversation.withAssistantUpdate(updated: Assistant, settings: Settings): Conversation {
    val previous = settings.getAssistantOf(this)
    val config = config
    val conversation = if (config == null) {
        this
    } else {
        copy(
            config = updated.toConversationConfig().copy(
                builtInSearch = if (updated.chatModelId == config.chatModelId) {
                    config.builtInSearch
                } else {
                    // 内置搜索是模型自身的能力，换模型后跟随新模型的开关
                    settings.findModelById(updated.chatModelId)?.tools?.contains(BuiltInTools.Search) == true
                }
            ),
            modeInjectionIds = updated.modeInjectionIds,
            lorebookIds = updated.lorebookIds,
        )
    }
    // 工作目录属于原来的工作区，换工作区后失效
    return if (updated.workspaceId != previous.workspaceId) conversation.copy(workspaceCwd = null) else conversation
}

/** 聊天页改动助手后写回助手设置的部分：会话持有的字段保持 [stored] 的原值。 */
fun Assistant.withoutConversationFields(conversation: Conversation, stored: Assistant): Assistant =
    if (conversation.config == null) {
        this
    } else {
        withConversationConfig(stored.toConversationConfig()).copy(
            modeInjectionIds = stored.modeInjectionIds,
            lorebookIds = stored.lorebookIds,
        )
    }

/**
 * 助手设置页修改了会话跟随的字段后，把这次的变化同步到已开始的会话上。
 *
 * 集合类配置（MCP、模式注入、Lorebook、技能）按本次增减同步：助手这次勾选或取消的项同样作用到会话，
 * 会话里其余已选状态保留；单值类配置只在助手本次改动了它时跟随，换模型和工作区的处理与首次固定相同。
 * 会话还没开始（[Conversation.config] 为 null）时本来就跟随助手，原样返回。
 */
fun Conversation.withStoredAssistantDiff(old: Assistant, new: Assistant, settings: Settings): Conversation {
    val config = config ?: return this
    var nextConfig = config
    var nextModeInjectionIds = modeInjectionIds
    var nextLorebookIds = lorebookIds
    var nextWorkspaceCwd = workspaceCwd

    if (old.mcpServers != new.mcpServers) {
        nextConfig = nextConfig.copy(
            // 会话里与助手重合的跟随助手换掉，会话自己勾的（不在旧助手里）保留
            mcpServers = ((nextConfig.mcpServers - old.mcpServers) + new.mcpServers)
                .filterTo(mutableSetOf()) { id -> settings.mcpServers.any { it.id == id } }
        )
    }
    if (old.modeInjectionIds != new.modeInjectionIds) {
        nextModeInjectionIds = ((nextModeInjectionIds - old.modeInjectionIds) + new.modeInjectionIds)
            .filterTo(mutableSetOf()) { id -> settings.modeInjections.any { it.id == id } }
    }
    if (old.lorebookIds != new.lorebookIds) {
        nextLorebookIds = ((nextLorebookIds - old.lorebookIds) + new.lorebookIds)
            .filterTo(mutableSetOf()) { id -> settings.lorebooks.any { it.id == id } }
    }
    if (old.enabledSkills != new.enabledSkills) {
        nextConfig = nextConfig.copy(
            enabledSkills = (nextConfig.enabledSkills - old.enabledSkills) + new.enabledSkills
        )
    }
    if (old.chatModelId != new.chatModelId) {
        nextConfig = nextConfig.copy(
            chatModelId = new.chatModelId,
            // 内置搜索是模型自身的能力，换模型后跟随新模型的开关
            builtInSearch = settings.findModelById(new.chatModelId)?.tools?.contains(BuiltInTools.Search) == true,
        )
    }
    if (old.reasoningLevel != new.reasoningLevel) {
        nextConfig = nextConfig.copy(reasoningLevel = new.reasoningLevel)
    }
    if (old.enableWebSearch != new.enableWebSearch) {
        nextConfig = nextConfig.copy(enableWebSearch = new.enableWebSearch)
    }
    if (old.workspaceId != new.workspaceId) {
        nextConfig = nextConfig.copy(workspaceId = new.workspaceId)
        // 工作目录属于原来的工作区，换工作区后失效
        nextWorkspaceCwd = null
    }
    if (old.workspaceAgentMode != new.workspaceAgentMode) {
        nextConfig = nextConfig.copy(workspaceAgentMode = new.workspaceAgentMode)
    }

    if (nextConfig == config && nextModeInjectionIds == modeInjectionIds &&
        nextLorebookIds == lorebookIds && nextWorkspaceCwd == workspaceCwd
    ) {
        return this
    }
    return copy(
        config = nextConfig,
        modeInjectionIds = nextModeInjectionIds,
        lorebookIds = nextLorebookIds,
        workspaceCwd = nextWorkspaceCwd,
    )
}

/** 会话所属助手自身的设置，不含会话上固定的配置；助手被删除后退回当前助手。 */
fun Settings.getStoredAssistantOf(conversation: Conversation): Assistant =
    getAssistantById(conversation.assistantId) ?: getCurrentAssistant()

/** 会话所属的助手（会话视角）。 */
fun Settings.getAssistantOf(conversation: Conversation): Assistant {
    val assistant = getStoredAssistantOf(conversation).withConversation(conversation)
    if (conversation.config == null) return assistant
    // 助手自身的失效引用在读取设置时已清理，会话上固定的这份在这里滤掉
    return assistant.copy(
        mcpServers = assistant.mcpServers.filterTo(mutableSetOf()) { id -> mcpServers.any { it.id == id } },
        modeInjectionIds = assistant.modeInjectionIds
            .filterTo(mutableSetOf()) { id -> modeInjections.any { it.id == id } },
        lorebookIds = assistant.lorebookIds.filterTo(mutableSetOf()) { id -> lorebooks.any { it.id == id } },
    )
}

/** 会话使用的聊天模型；会话开始后内置搜索以会话上固定的开关为准。 */
fun Settings.getChatModelOf(conversation: Conversation): Model? {
    val assistantModel = findModelById(getStoredAssistantOf(conversation).chatModelId ?: chatModelId)
    val config = conversation.config ?: return assistantModel
    // 固定的模型被删除后退回助手当前的模型，内置搜索也跟随该模型自身的开关
    val model = findModelById(config.chatModelId) ?: return assistantModel
    return model.copy(
        tools = if (config.builtInSearch) model.tools + BuiltInTools.Search else model.tools - BuiltInTools.Search
    )
}

/**
 * 把助手当前的配置固定到会话上。已经固定过的会话原样返回，
 * 只有固定的模型被删除时才改为固定助手当前的模型，之后不再跟着助手变。
 */
fun Conversation.bindConfig(settings: Settings): Conversation {
    val model = settings.getChatModelOf(this)
    val builtInSearch = model != null && BuiltInTools.Search in model.tools
    val config = config
    if (config != null) {
        if (model == null || model.id == config.chatModelId) return this
        return copy(config = config.copy(chatModelId = model.id, builtInSearch = builtInSearch))
    }
    val assistant = settings.getAssistantOf(this)
    return copy(
        config = assistant.toConversationConfig().copy(chatModelId = model?.id, builtInSearch = builtInSearch),
        // 旧版本允许会话单独绑定注入，这类会话保留自己的绑定
        modeInjectionIds = modeInjectionIds.ifEmpty { assistant.modeInjectionIds },
        lorebookIds = lorebookIds.ifEmpty { assistant.lorebookIds },
    )
}
