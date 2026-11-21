package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 工作区智能体的执行模式（类似 OpenCode 的 Build / Plan 模式）。
 */
@Serializable
enum class WorkspaceAgentMode {
    /** 完整读写模式：开放全部工作区工具，按工作区审批策略放行 */
    BUILD,

    /** 规划模式：只读探索代码与环境、与用户讨论方案，不修改文件、不执行有副作用的命令 */
    PLAN,
}

/**
 * 工作区工具审批策略。
 *
 * 旧的按工具布尔开关序列化在 [me.rerere.rikkahub.data.db.entity.WorkspaceEntity.toolApprovals]，
 * 读取时 `false`（免审）映射为 [ALWAYS_ALLOW]、`true`（必审）映射为 [ALWAYS_ASK]。
 */
@Serializable
enum class WorkspaceApprovalPolicy {
    /** 智能放行：只读与工作区内安全操作自动放行，高危指令（删除、系统修改、网络安装等）才请求审批 */
    SMART,

    /** 全部自动放行，不弹审批 */
    ALWAYS_ALLOW,

    /** 每次调用都请求审批 */
    ALWAYS_ASK,
}
