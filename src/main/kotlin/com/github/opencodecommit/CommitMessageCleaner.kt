package com.github.opencodecommit

/**
 * 把 opencode 的原始输出清洗成可以直接写进 Commit 输入框的提交信息。
 */
object CommitMessageCleaner {

    /** opencode 格式化输出里用于表示工具调用的前缀符号 */
    private val TOOL_MARKS = charArrayOf(
        '\u2699', // ⚙
        '\u2731', // ✱
        '\u2305', // ⌁
        '\u2394', // ⎔
        '\u2713', // ✓
        '\u23F5', // ⏵
        '\u00B7', // ·
        '\u2192', // →
        '\u279C', // ➜
        '\u25B8'  // ▸
    )

    private val TOOL_PREFIXES = listOf(
        "Read ", "Bash ", "Edit ", "Write ", "Glob ", "Grep ", "Task ", "Todo ",
        "WebFetch ", "Patch ", "List ", "ApplyPatch ", "MultiEdit "
    )

    private val CONVENTIONAL_FIRST_LINE = Regex(
        """^\s*(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\([^()\s]+\))?!?:\s*\S.*$"""
    )

    private val PREAMBLE = Regex(
        """^(here('s| is)?|sure,?|certainly,?|commit message:|the commit message is:|generated commit message:).*""",
        RegexOption.IGNORE_CASE
    )

    fun clean(raw: String): String {
        if (raw.isBlank()) return ""

        var lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').filter { !isNoise(it) }

        // 若前面还堆着别的内容，从第一条符合 Conventional Commits 首行的记录开始截
        val conventionalStart = lines.indexOfFirst { CONVENTIONAL_FIRST_LINE.matches(it) }
        if (conventionalStart > 0) {
            lines = lines.subList(conventionalStart, lines.size)
        }

        while (lines.isNotEmpty() && (lines.first().isBlank() || PREAMBLE.matches(lines.first().trim()))) {
            lines = lines.drop(1)
        }

        val joined = lines.joinToString("\n") { it.trimEnd() }.trim()
        return joined.replace("**", "")
    }

    private fun isNoise(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("```")) return true
        if (trimmed.startsWith("# ")) return true
        if (trimmed.startsWith("|")) return true
        if (TOOL_MARKS.contains(trimmed[0])) return true
        return TOOL_PREFIXES.any { trimmed.startsWith(it) }
    }
}
