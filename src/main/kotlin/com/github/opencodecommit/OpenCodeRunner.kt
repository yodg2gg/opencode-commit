package com.github.opencodecommit

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 调用本地 opencode CLI：opencode run --file <diff> "instruction"
 */
object OpenCodeRunner {

    private val LOG = Logger.getInstance(OpenCodeRunner::class.java)

    class OpenCodeException(message: String, val detail: String = "") : Exception(message)

    fun generate(project: Project, diffText: String, indicator: ProgressIndicator?): String {
        val settings = OpenCodeCommitSettings.getInstance()
        val attachment = FileUtil.createTempFile("opencode-commit-", ".diff")
        attachment.writeText(diffText, StandardCharsets.UTF_8)

        val command = buildCommand(project, settings, attachment)
        LOG.debug("OpenCode command: " + command.commandLineString)

        val handler = CapturingProcessHandler(command)
        handler.setShouldDestroyProcessRecursively(true)
        try {
            // opencode run 支持 stdin 管道输入，检测到 stdin 不关闭会一直等待；
            // 必须显式关闭子进程的 stdin，表示没有管道输入。
            handler.process.outputStream.close()
        } catch (_: Exception) {
        }

        val output: ProcessOutput = try {
            if (indicator != null) {
                handler.runProcessWithProgressIndicator(indicator, settings.timeoutSeconds * 1000)
            } else {
                handler.runProcess(settings.timeoutSeconds * 1000)
            }
        } catch (e: Exception) {
            throw OpenCodeException("执行 OpenCode 失败：${e.message}")
        } finally {
            FileUtil.asyncDelete(attachment)
        }

        val message = parse(output, settings)
        if (message.isBlank()) {
            throw OpenCodeException("OpenCode 没有返回可用的提交信息", buildDetail(output))
        }
        return message
    }

    private fun parse(output: ProcessOutput, settings: OpenCodeCommitSettings): String {
        val jsonMode = settings.outputFormat == OpenCodeCommitSettings.FORMAT_JSON

        var raw = if (jsonMode) JsonTextExtractor.extractText(output.stdout) else output.stdout
        var message = CommitMessageCleaner.clean(raw)

        if (message.isBlank()) {
            // 有些版本/配置下文本会落到 stderr
            raw = if (jsonMode) JsonTextExtractor.extractText(output.stderr) else output.stderr
            message = CommitMessageCleaner.clean(raw)
        }
        if (message.isBlank() && jsonMode) {
            // JSON 解析没拿到东西，看看是不是 API 报错（如未配置模型 key）
            val apiError = JsonTextExtractor.extractError(output.stdout)
            if (!apiError.isNullOrBlank()) {
                throw OpenCodeException("OpenCode 模型调用失败：$apiError", buildDetail(output))
            }
            // 最后再直接用原始输出兜底一次
            message = CommitMessageCleaner.clean(output.stdout)
        }
        return message
    }

    private fun buildCommand(
        project: Project,
        settings: OpenCodeCommitSettings,
        attachment: File
    ): GeneralCommandLine {
        val command = GeneralCommandLine()
        command.exePath = settings.executablePath.trim().ifBlank { "opencode" }
        command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        command.withCharset(StandardCharsets.UTF_8)

        command.addParameters("run")
        if (settings.outputFormat == OpenCodeCommitSettings.FORMAT_JSON) {
            command.addParameters("--format", "json")
        }
        if (settings.model.isNotBlank()) command.addParameters("--model", settings.model.trim())
        if (settings.agent.isNotBlank()) command.addParameters("--agent", settings.agent.trim())
        if (settings.attachUrl.isNotBlank()) command.addParameters("--attach", settings.attachUrl.trim())

        val extra = settings.extraArgs.trim()
        if (extra.isNotBlank()) {
            extra.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { command.addParameter(it) }
        }

        // 注意：--file 是 yargs 的 array 选项，会吞掉其后所有普通参数；
        // 因此 positional 提示词必须放在 --file 之前，且 --file 用等号形式只取一个值。
        command.addParameter(instructionForShell(settings))
        command.addParameter("--file=" + attachment.absolutePath)

        val basePath = project.basePath
        if (!basePath.isNullOrBlank()) {
            command.withWorkDirectory(File(basePath))
        }
        command.withEnvironment("NO_COLOR", "1")
        command.withEnvironment("FORCE_COLOR", "0")
        command.withEnvironment("CLICOLOR", "0")
        return command
    }

    /**
     * Windows 上把可执行文件填成 .cmd/.bat 时，进程实际由 cmd.exe 转发（cmd /c xxx.cmd ...）。
     * cmd.exe 会把命令行里的换行当成命令分隔符：多行提示词在第一个换行处就被截断，
     * 排在它后面的 --file 也会被当成新命令直接丢掉——模型只收到提示词第一行，
     * 既没有语言要求也没有 diff，于是输出英文。
     * 实测：多行参数经 cmd 后只剩 1 个；压成单行后 5 个参数全部完整送达。
     * 所以走 cmd 层时把换行和缩进压成单空格，直达 exe 时保持原样。
     */
    private fun instructionForShell(settings: OpenCodeCommitSettings): String {
        val instruction = buildInstruction(settings)
        val exe = settings.executablePath.trim()
        val viaCmdShell = SystemInfo.isWindows && (exe.endsWith(".cmd", true) || exe.endsWith(".bat", true))
        return if (viaCmdShell) instruction.replace(Regex("\\s*\\R+\\s*"), " ") else instruction
    }

    private fun buildInstruction(settings: OpenCodeCommitSettings): String {
        return settings.promptTemplate
            .replace("{language}", settings.messageLanguage)
            .replace("{subject_limit}", settings.subjectLimit.toString())
    }

    private fun buildDetail(output: ProcessOutput): String {
        val builder = StringBuilder()
        builder.append("exit code: ").append(output.exitCode)
        val stderr = output.stderr.trim()
        if (stderr.isNotEmpty()) {
            builder.append(System.lineSeparator()).append("stderr: ").append(stderr.take(600))
        }
        val stdout = output.stdout.trim()
        if (stdout.isNotEmpty()) {
            builder.append(System.lineSeparator()).append("stdout: ").append(stdout.take(600))
        }
        return builder.toString()
    }
}
