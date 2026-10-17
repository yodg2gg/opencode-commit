package com.github.opencodecommit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * 打开本插件的设置页（Settings ▸ Tools ▸ opencode Commit Message）。
 *
 * 这个动作不挂在任何菜单上，只作为通知气泡里的按钮出现，所以文本直接写在构造函数里，
 * 也没有 update() 覆写（不设条件，按钮恒可用）。
 *
 * 坑：ShowSettingsUtil#showSettingsDialog(Project, String) 的第二个参数是
 * 「要选中页面的 displayName」而不是 configurable 的 id —— IC 2024.3 内部走的是
 * findPreselectedByDisplayName，用 getDisplayName() 做精确相等比较，传 id 会选不中。
 */
class OpenOpenCodeCommitSettingsAction : AnAction("Open Settings...") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, OpenCodeCommitConfigurable.DISPLAY_NAME)
    }
}
