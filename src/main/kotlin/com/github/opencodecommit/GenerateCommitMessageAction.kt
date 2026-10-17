package com.github.opencodecommit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.vcs.commit.CommitWorkflowUi
import java.awt.datatransfer.StringSelection

/**
 * 生成提交信息：Commit 工具窗口提交信息区右上角的按钮，以及 Local Changes 右键菜单项。
 *
 * 单点即生成，不做下拉菜单（IC 2024.3 里按钮只有一个点击目标，做成下拉就必须多点一次）。
 * 设置入口放在生成后弹出的通知气泡里。
 * 优先用提交窗口里勾选（included）的文件；一个都没勾选时交给暂存区逻辑。
 */
class GenerateCommitMessageAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val inCommitWindow = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) != null
        val hasChangesSelection = e.getData(VcsDataKeys.CHANGES) != null
        e.presentation.isEnabledAndVisible = e.project != null && (inCommitWindow || hasChangesSelection)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workflowUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)

        val selected: List<Change> = if (workflowUi != null) {
            // 提交窗口里：以勾选（included）的文件为准，一个都没勾选时交给暂存区逻辑
            workflowUi.getIncludedChanges().toList()
        } else {
            e.getData(VcsDataKeys.CHANGES)?.toList() ?: emptyList()
        }

        object : Task.Backgroundable(project, "OpenCode 正在生成提交信息", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    indicator.text = "收集改动…"
                    val diff = DiffCollector.collect(project, selected, OpenCodeCommitSettings.getInstance())
                    indicator.text = "OpenCode 正在生成提交信息…"
                    indicator.text2 = diff.sourceLabel + (if (diff.truncated) "（内容已截断）" else "")
                    val message = OpenCodeRunner.generate(project, diff.text, indicator)
                    runInEdt(project) { deliver(project, workflowUi, message) }
                } catch (ex: Exception) {
                    val title: String
                    val content: String
                    when (ex) {
                        is DiffException -> {
                            title = "无法生成提交信息"
                            content = escape(ex.message ?: "")
                        }
                        is OpenCodeRunner.OpenCodeException -> {
                            title = "OpenCode 生成失败"
                            val text = StringBuilder(ex.message ?: "")
                            if (ex.detail.isNotBlank()) text.append("\n").append(ex.detail)
                            content = escape(text.toString())
                        }
                        else -> {
                            title = "OpenCode 生成失败"
                            content = escape(ex.message ?: ex.toString())
                        }
                    }
                    runInEdt(project) { notify(project, title, content, NotificationType.ERROR) }
                }
            }
        }.queue()
    }

    private fun deliver(project: Project, workflowUi: CommitWorkflowUi?, message: String) {
        if (workflowUi != null) {
            workflowUi.commitMessageUi.setText(message)
            notify(project, "OpenCode 已生成提交信息", "已写入提交信息输入框", NotificationType.INFORMATION)
            return
        }
        // 没有提交窗口（例如在 Local Changes 里右键），复制到剪贴板并弹出可编辑对话框
        CopyPasteManager.getInstance().setContents(StringSelection(message))
        Messages.showMultilineInputDialog(
            project,
            "生成的提交信息（已复制到剪贴板，可在下方直接编辑）：",
            "OpenCode Commit Message",
            message,
            null,
            null
        )
        notify(project, "OpenCode 已生成提交信息", "已复制到剪贴板", NotificationType.INFORMATION)
    }

    private fun runInEdt(project: Project, action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed) action()
            },
            ModalityState.defaultModalityState()
        )
    }

    /**
     * 所有通知都会挂一个「Open Settings...」按钮：这是本插件设置页的快捷入口
     * （按钮在气泡里，同时也会留在 Event Log 里）。
     * 每次都新建实例，避免多个气泡共享同一个 AnAction 的 Presentation。
     */
    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("opencode.commit")
            .createNotification(title, content, type)
            .addAction(OpenOpenCodeCommitSettingsAction())
            .notify(project)
    }

    private fun escape(text: String): String {
        return StringUtil.escapeXmlEntities(text).replace("\n", "<br/>")
    }
}
