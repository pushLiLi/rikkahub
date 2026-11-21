package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Blueprint
import me.rerere.hugeicons.stroke.Hammer
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.WorkspaceAgentMode
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.components.ToggleSurface
import me.rerere.ui.toast.ToastType

/**
 * 工作区执行模式切换按钮（Build / Plan，类似 OpenCode）。
 *
 * 点击在两个模式之间直接切换，并给出震动 + Toast 反馈：
 * - [WorkspaceAgentMode.BUILD]：完整读写，按工作区审批策略放行（锤子 = 动手施工）
 * - [WorkspaceAgentMode.PLAN]：只读探索与规划，写操作与危险命令需要审批（蓝图 = 先规划后施工）
 */
@Composable
fun WorkspaceModeButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    mode: WorkspaceAgentMode,
    onUpdateMode: (WorkspaceAgentMode) -> Unit,
) {
    val isPlan = mode == WorkspaceAgentMode.PLAN
    val hapticFeedback = LocalHapticFeedback.current
    val toaster = LocalToaster.current
    val planToast = stringResource(R.string.workspace_mode_plan_toast)
    val buildToast = stringResource(R.string.workspace_mode_build_toast)

    ToggleSurface(
        // Plan 是需要显式提醒的特殊状态，用选中态高亮
        checked = isPlan,
        onClick = {
            val next = if (isPlan) WorkspaceAgentMode.BUILD else WorkspaceAgentMode.PLAN
            hapticFeedback.performHapticFeedback(
                if (next == WorkspaceAgentMode.PLAN) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
            )
            toaster.show(
                message = if (next == WorkspaceAgentMode.PLAN) planToast else buildToast,
                type = ToastType.Info,
            )
            onUpdateMode(next)
        },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlan) HugeIcons.Blueprint else HugeIcons.Hammer,
                    contentDescription = stringResource(
                        if (isPlan) R.string.workspace_mode_plan else R.string.workspace_mode_build
                    ),
                )
            }
            if (!onlyIcon) {
                Text(
                    text = stringResource(
                        if (isPlan) R.string.workspace_mode_plan else R.string.workspace_mode_build
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
