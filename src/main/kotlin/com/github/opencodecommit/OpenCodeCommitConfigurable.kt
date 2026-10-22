package com.github.opencodecommit

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JButton

class OpenCodeCommitConfigurable : BoundConfigurable(DISPLAY_NAME) {

    companion object {
        /**
         * 必须与 plugin.xml 里 <applicationConfigurable displayName="..."> 完全一致。
         * 打开设置页时是按这个字符串精确匹配的，改一处就得改两处。
         */
        const val DISPLAY_NAME = "OpenCode Commit Message"

        /** 拉模型列表允许的时间；首次运行可能要下载 models.dev 的缓存，放宽一点 */
        private const val FETCH_TIMEOUT_MS = 60000
    }

    private val settings = OpenCodeCommitSettings.getInstance()
    private val config get() = settings.uiState

    /** 「获取模型列表」要写入的目标输入框 / 要禁用的按钮，createPanel() 里赋值 */
    private var modelField: JBTextField? = null
    private var fetchButton: JButton? = null

    /**
     * 「可执行文件」输入框。bindText 只在 Apply 时才写回 config，
     * 而「验证 OpenCode」「获取模型列表」需要实时取输入框的当前值，否则改完路径不点 Apply 用的还是旧值。
     */
    private var executableField: JBTextField? = null

    override fun createPanel() = panel {
        group("OpenCode 命令") {
            row("可执行文件") {
                executableField = textField()
                    .bindText(config::executablePath)
                    .applyToComponent { columns = 28 }
                    .comment("已加入 PATH 时填 opencode；否则填绝对路径（Windows 例如 C:/Users/me/AppData/Roaming/npm/opencode.cmd）")
                    .component
            }
            row("模型") {
                modelField = textField()
                    .bindText(config::model)
                    .applyToComponent { columns = 28 }
                    .comment("留空使用 OpenCode 默认模型；点右侧按钮从 OpenCode 拉取可用模型列表，也可手填如 anthropic/claude-sonnet-4")
                    .component
                fetchButton = button("获取模型列表") { _ -> fetchModels() }.component
            }
            row("Agent") {
                textField()
                    .bindText(config::agent)
                    .applyToComponent { columns = 28 }
                    .comment("留空使用默认 agent")
            }
            row("Attach URL") {
                textField()
                    .bindText(config::attachUrl)
                    .applyToComponent { columns = 28 }
                    .comment("常驻服务地址，如 http://localhost:4096（先运行 opencode serve），可跳过冷启动；留空则每次新起本地服务")
            }
            row("额外参数") {
                textField()
                    .bindText(config::extraArgs)
                    .applyToComponent { columns = 28 }
                    .comment("附加到 opencode run 上，例如 --auto（自动批准权限请求）")
            }
            row("输出格式") {
                comboBox(listOf(OpenCodeCommitSettings.FORMAT_JSON, OpenCodeCommitSettings.FORMAT_TEXT))
                    .bindItem({ config.outputFormat }, { config.outputFormat = it ?: OpenCodeCommitSettings.FORMAT_JSON })
                    .comment("json = 解析 --format json 原始事件（推荐）；text = 直接取格式化输出并清洗工具行")
            }
            row("超时（秒）") {
                intTextField(10..3600)
                    .bindIntText(config::timeoutSeconds)
            }
            row {
                button("验证 OpenCode") { _ -> checkExecutable() }
                    .comment("执行一次 --version，确认可执行文件可以被找到")
            }
        }

        group("提交信息") {
            row("语言") {
                comboBox(listOf(OpenCodeCommitSettings.LANG_CHINESE, OpenCodeCommitSettings.LANG_ENGLISH))
                    .bindItem({ config.messageLanguage }, { config.messageLanguage = it ?: OpenCodeCommitSettings.LANG_CHINESE })
            }
            row("subject 长度上限") {
                intTextField(20..200)
                    .bindIntText(config::subjectLimit)
            }
            row("diff 最大字符数") {
                intTextField(1000..200000)
                    .bindIntText(config::maxDiffLength)
                    .comment("超过上限会截断，避免请求过大")
            }
            row {
                checkBox("未勾选时把 Unversioned Files 也纳入")
                    .bindSelected(config::includeUnversionedWhenNoSelection)
                    .comment("提交窗口里什么都没勾选时，除全部改动外，是否连未跟踪的新文件一起交给 OpenCode（未跟踪文件可能较多）")
            }
            row {
                checkBox("附带最近 5 条提交标题作为风格参考")
                    .bindSelected(config::includeRecentCommits)
            }
        }

        group("提示词") {
            row {
                textArea()
                    .bindText(config::promptTemplate)
                    .applyToComponent { rows = 16; columns = 60 }
                    .comment("占位符：{language} 语言、{subject_limit} subject 长度上限")
            }
        }
    }

    /** 实时取「可执行文件」输入框的当前值；面板未打开时回落到已应用的配置 */
    private fun currentExecutable(): String =
        (executableField?.text ?: config.executablePath).trim().ifBlank { "opencode" }

    /** 拉取结果：Ok 是模型 id 列表，Fail 是给用户看的错误文案 */
    private sealed interface FetchOutcome {
        data class Ok(val models: List<String>) : FetchOutcome
        data class Fail(val message: String) : FetchOutcome
    }

    /**
     * 后台执行 `opencode models`，结束后在输入框下方弹出可选模型列表。
     * 走进程而不是 EDT（项目约定），结束后用 stateForComponent 回到设置页所在的模态层。
     */
    private fun fetchModels() {
        val field = modelField ?: return
        val button = fetchButton

        val command = GeneralCommandLine()
        command.exePath = currentExecutable()
        command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        command.withCharset(java.nio.charset.StandardCharsets.UTF_8)
        command.addParameters("models")

        button?.isEnabled = false
        button?.text = "获取中…"

        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = try {
                val output = CapturingProcessHandler(command).runProcess(FETCH_TIMEOUT_MS)
                // 实测输出为 CRLF、每行一个 provider/model；trim 会剥掉 \r。
                // 过滤空行、注释行和带空格的行（模型 id 不会有空格，用于挡掉日志/提示性文字）。
                val list = output.stdout.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && !it.contains(' ') }
                    .distinct()
                    .sorted()
                if (output.exitCode == 0 && list.isNotEmpty()) {
                    FetchOutcome.Ok(list)
                } else {
                    FetchOutcome.Fail(
                        output.stderr.trim()
                            .ifBlank { output.stdout.trim() }
                            .ifBlank { "OpenCode 退出码 ${output.exitCode}" }
                    )
                }
            } catch (e: Exception) {
                FetchOutcome.Fail("执行 OpenCode 失败：${e.message}")
            }

            ApplicationManager.getApplication().invokeLater({
                button?.isEnabled = true
                button?.text = "获取模型列表"
                if (!field.isShowing) return@invokeLater
                when (outcome) {
                    is FetchOutcome.Ok -> showModelChooser(field, outcome.models)
                    is FetchOutcome.Fail -> Messages.showErrorDialog(
                        field,
                        outcome.message,
                        "获取模型列表失败"
                    )
                }
            }, ModalityState.stateForComponent(field))
        }
    }

    private fun showModelChooser(field: JBTextField, models: List<String>) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(models)
            .setTitle("选择模型（共 ${models.size} 个）")
            .setFilterAlwaysVisible(true)
            .setNamerForFiltering { it }
            .setResizable(true)
            .setAdText("输入关键字过滤，回车选中")
            .setItemChosenCallback { chosen -> field.text = chosen }
            .createPopup()
            .showUnderneathOf(field)
    }

    private fun checkExecutable() {
        val command = GeneralCommandLine()
        command.exePath = currentExecutable()
        command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        command.withCharset(java.nio.charset.StandardCharsets.UTF_8)
        command.addParameters("--version")
        val result = try {
            val output = CapturingProcessHandler(command).runProcess(15000)
            if (output.exitCode == 0) {
                "OpenCode 可用：" + output.stdout.trim()
            } else {
                "OpenCode 退出码 ${output.exitCode}：" + output.stderr.trim().ifBlank { output.stdout.trim() }
            }
        } catch (e: Exception) {
            "执行 OpenCode 失败：${e.message}"
        }
        Messages.showInfoMessage(result, "OpenCode 连通性检查")
    }
}
