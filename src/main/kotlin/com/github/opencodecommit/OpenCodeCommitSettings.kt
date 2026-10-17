package com.github.opencodecommit

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 插件设置（应用级持久化）。
 */
@Service(Service.Level.APP)
@State(name = "OpenCodeCommitSettings", storages = [Storage("openCodeCommit.xml")])
class OpenCodeCommitSettings : PersistentStateComponent<OpenCodeCommitSettings.State> {

    class State {
        /** opencode 可执行文件：写 "opencode" 时依赖 PATH；也可写绝对路径，如 C:/Users/me/AppData/.../opencode.cmd */
        var executablePath: String = "opencode"

        /** -m/--model，留空使用 opencode 默认模型 */
        var model: String = ""

        /** --agent，留空使用默认 agent */
        var agent: String = ""

        /** --attach，如 http://localhost:4096；留空表示每次新起本地服务 */
        var attachUrl: String = ""

        /** 追加到 opencode run 的自定义参数，如 --auto */
        var extraArgs: String = ""

        /** 输出格式：json（原始 JSON 事件，解析更干净）或 text（格式化输出，需清洗工具行） */
        var outputFormat: String = FORMAT_JSON

        /** 提交信息语言 */
        var messageLanguage: String = LANG_CHINESE

        /** 首行 subject 最大长度（会写入提示词） */
        var subjectLimit: Int = 72

        /** 单次 opencode 调用超时（秒） */
        var timeoutSeconds: Int = 180

        /** 交给 opencode 的 diff 最大字符数，超出部分截断 */
        var maxDiffLength: Int = 20000

        /** 无勾选且暂存区为空时，是否回落使用未暂存改动 */
        var fallbackToUnstaged: Boolean = true

        /** 未勾选时是否额外给 opencode 最近 5 条提交记录作为风格参考 */
        var includeRecentCommits: Boolean = false

        /** 提示词模板，占位符：{language} {subject_limit} */
        var promptTemplate: String = DEFAULT_PROMPT
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    /** 供设置页绑定的可变状态对象（不能叫 state，会和 getState() 撞 JVM 签名） */
    val uiState: State
        get() = myState

    val executablePath: String get() = myState.executablePath
    val model: String get() = myState.model
    val agent: String get() = myState.agent
    val attachUrl: String get() = myState.attachUrl
    val extraArgs: String get() = myState.extraArgs
    val outputFormat: String get() = myState.outputFormat
    val messageLanguage: String get() = myState.messageLanguage
    val subjectLimit: Int get() = myState.subjectLimit
    val timeoutSeconds: Int get() = myState.timeoutSeconds
    val maxDiffLength: Int get() = myState.maxDiffLength
    val fallbackToUnstaged: Boolean get() = myState.fallbackToUnstaged
    val includeRecentCommits: Boolean get() = myState.includeRecentCommits
    val promptTemplate: String get() = myState.promptTemplate

    companion object {
        const val FORMAT_JSON = "json"
        const val FORMAT_TEXT = "text"

        const val LANG_ENGLISH = "English"
        const val LANG_CHINESE = "Simplified Chinese"

        const val DEFAULT_PROMPT = """You are an expert at writing Conventional Commits messages.

A git diff is attached to this message. Write exactly ONE commit message for it.

Output requirements:
1. First line: <type>[(<scope>)]: <subject>
   - type is exactly one of: feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert
   - scope is optional, lowercase, and names the module or area affected
   - subject uses imperative mood, stays lowercase, has no trailing period, and is at most {subject_limit} characters
2. Then a blank line, then an optional body of at most 3 short lines. Explain what changed and why, not how.
3. Only when the change breaks compatibility, add a footer line: BREAKING CHANGE: <what broke>.
4. Write the whole message in {language}.
5. Output the commit message ONLY: no code fences, no headings, no "Here is ...", no commentary before or after it.
6. Do NOT call any tool. Do NOT create, read or modify any file. Answer from the attached diff alone.
"""

        @JvmStatic
        fun getInstance(): OpenCodeCommitSettings = service()
    }
}
