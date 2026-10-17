package com.github.opencodecommit

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitUtil
import git4idea.repo.GitRepository
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class DiffException(message: String) : Exception(message)

/**
 * 收集交给 opencode 的改动内容。
 *
 * 规则：
 * 1. 有勾选文件（Commit 工具窗口里 included changes / Changes 视图里选中的 changes）→ 只用这些文件；
 * 2. 没有勾选 → 只用暂存区(staged) 的 diff；
 * 3. 暂存区为空且设置允许 → 回落到未暂存改动；
 * 4. 未跟踪的新文件无法由 git diff 表达，改为附加内容快照。
 */
object DiffCollector {

    data class Result(
        val text: String,
        val fileCount: Int,
        val sourceLabel: String,
        val truncated: Boolean
    )

    private const val MAX_NEW_FILE_LINES = 300
    private const val GIT_TIMEOUT_MS = 30_000

    fun collect(project: Project, selected: List<Change>, settings: OpenCodeCommitSettings): Result {
        val repositoryManager = GitUtil.getRepositoryManager(project)
        val repositories = repositoryManager.repositories
        if (repositories.isEmpty()) {
            throw DiffException("当前项目不是 Git 仓库，无法生成提交信息")
        }

        val hasSelection = selected.isNotEmpty()
        val groups = LinkedHashMap<GitRepository, MutableList<FilePath>>()
        if (hasSelection) {
            for (change in selected) {
                val path = change.beforeRevision?.file ?: change.afterRevision?.file ?: continue
                val repo = repositoryManager.getRepositoryForFile(path) ?: continue
                groups.getOrPut(repo) { ArrayList() }.add(path)
            }
            if (groups.isEmpty()) {
                throw DiffException("选中的文件都不属于当前项目的 Git 仓库")
            }
        } else {
            for (repo in repositories) groups[repo] = ArrayList()
        }

        val buffer = StringBuilder()
        var truncated = false
        var sawContent = false

        for ((repo, paths) in groups) {
            val filters: List<String>? = if (hasSelection) {
                paths.mapNotNull { relativePath(repo, it) }.filter { it.isNotBlank() }.ifEmpty { null }
            } else null

            val statusOutput = runGit(repo, listOf("-c", "core.quotepath=false", "status", "--porcelain", "-z"), filters)
            val records = statusOutput.split('\u0000').filter { it.isNotBlank() }
            if (records.isEmpty()) continue

            val staged = LinkedHashSet<String>()
            val unstaged = LinkedHashSet<String>()
            val untracked = LinkedHashSet<String>()
            var index = 0
            while (index < records.size) {
                val record = records[index]
                index++
                if (record.length < 3) continue
                val xy = record.substring(0, 2)
                val rel = record.substring(3)
                if (rel.isBlank()) continue
                val x = xy[0]
                val y = xy[1]
                // 重命名/复制记录后面紧跟一个原始路径记录，需要跳过
                if (x == 'R' || x == 'C') index++
                if (x == '?' && y == '?') {
                    untracked.add(rel)
                    continue
                }
                if (x == '!') {
                    // 被忽略的文件
                    continue
                }
                // 部分暂存的文件会同时出现在两个集合里，暂存和未暂存内容都要给模型
                if (x != ' ' && x != '?') staged.add(rel)
                if (y != ' ' && y != '?') unstaged.add(rel)
            }

            val useUnstaged = hasSelection || (settings.fallbackToUnstaged && staged.isEmpty())
            val useUntracked = hasSelection || (settings.fallbackToUnstaged && staged.isEmpty())

            if (staged.isNotEmpty()) {
                sawContent = true
                if (!appendChunk(buffer, "### staged changes (git diff --cached)\n", settings.maxDiffLength)) truncated = true
                val diff = gitDiff(repo, cached = true, paths = staged.toList())
                if (diff.isNotBlank() && !appendChunk(buffer, diff + "\n", settings.maxDiffLength)) truncated = true
            }

            if (useUnstaged && unstaged.isNotEmpty()) {
                sawContent = true
                if (!appendChunk(buffer, "### unstaged changes (git diff)\n", settings.maxDiffLength)) truncated = true
                val diff = gitDiff(repo, cached = false, paths = unstaged.toList())
                if (diff.isNotBlank() && !appendChunk(buffer, diff + "\n", settings.maxDiffLength)) truncated = true
            }

            if (useUntracked) {
                for (rel in untracked) {
                    val patch = newFilePatch(repo, rel, settings.maxDiffLength)
                    if (patch.isBlank()) continue
                    sawContent = true
                    if (!appendChunk(buffer, "### new file (not tracked yet)\n", settings.maxDiffLength)) truncated = true
                    if (!appendChunk(buffer, patch + "\n", settings.maxDiffLength)) truncated = true
                }
            }
        }

        if (!sawContent) {
            throw DiffException(
                if (hasSelection) {
                    "勾选的文件没有任何可提交的改动"
                } else {
                    "暂存区没有改动。请先 git add，或在设置里开启“无暂存时回落使用未暂存改动”"
                }
            )
        }

        if (settings.includeRecentCommits) {
            for (repo in groups.keys) {
                val subjects = readRecentCommitSubjects(repo, 5)
                if (subjects.isNotBlank()) {
                    if (!appendChunk(buffer, "### recent commit subjects (style reference)\n", settings.maxDiffLength)) truncated = true
                    if (!appendChunk(buffer, subjects + "\n", settings.maxDiffLength)) truncated = true
                }
            }
        }

        if (truncated) {
            buffer.append("\n# [diff truncated, it exceeded ")
                .append(settings.maxDiffLength)
                .append(" characters]\n")
        }

        val text = buffer.toString()
        val fileCount = text.lineSequence().count { it.startsWith("diff --git ") }
        val label = if (hasSelection) "勾选的 ${selected.size} 个文件" else "暂存区"
        return Result(text, fileCount, label, truncated)
    }

    private fun relativePath(repo: GitRepository, path: FilePath): String? {
        return try {
            Path.of(repo.root.path).relativize(Path.of(path.path)).toString().replace('\\', '/')
        } catch (e: Exception) {
            null
        }
    }

    private fun appendChunk(buffer: StringBuilder, chunk: String, max: Int): Boolean {
        if (buffer.length >= max) return false
        val remaining = max - buffer.length
        if (chunk.length > remaining) {
            buffer.append(chunk, 0, remaining)
            return false
        }
        buffer.append(chunk)
        return true
    }

    private fun runGit(repo: GitRepository, args: List<String>, paths: List<String>?): String {
        val command = GeneralCommandLine()
        command.exePath = "git"
        command.withWorkDirectory(File(repo.root.path))
        command.withCharset(StandardCharsets.UTF_8)
        command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        command.addParameters(args)
        if (!paths.isNullOrEmpty()) {
            command.addParameter("--")
            for (p in paths) command.addParameter(p)
        }
        val output = try {
            CapturingProcessHandler(command).runProcess(GIT_TIMEOUT_MS)
        } catch (e: Exception) {
            return ""
        }
        return if (output.exitCode == 0) output.stdout else ""
    }

    private fun gitDiff(repo: GitRepository, cached: Boolean, paths: List<String>): String {
        if (paths.isEmpty()) return ""
        val args = mutableListOf("-c", "core.quotepath=false", "diff", "--no-color", "--no-ext-diff", "--find-renames")
        if (cached) args.add("--cached")
        return runGit(repo, args, paths)
    }

    private fun readRecentCommitSubjects(repo: GitRepository, count: Int): String {
        return runGit(repo, listOf("-c", "core.quotepath=false", "log", "-$count", "--pretty=format:%h %s"), null)
    }

    private fun newFilePatch(repo: GitRepository, relativePath: String, maxLength: Int): String {
        val filePath = LocalFilePath("${repo.root.path}/$relativePath", false)
        val virtualFile: VirtualFile = filePath.virtualFile ?: return ""
        if (!virtualFile.isValid) return ""
        if (virtualFile.fileType.isBinary) {
            return "diff --git a/$relativePath b/$relativePath\nnew file mode 100644\nBinary file $relativePath added\n"
        }
        val content = try {
            ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(virtualFile) }
        } catch (e: Exception) {
            return ""
        }
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val kept = lines.take(MAX_NEW_FILE_LINES)
        val patch = StringBuilder()
        patch.append("diff --git a/").append(relativePath).append(" b/").append(relativePath).append('\n')
        patch.append("new file mode 100644\n")
        patch.append("--- /dev/null\n")
        patch.append("+++ b/").append(relativePath).append('\n')
        patch.append("@@ -0,0 +1,").append(kept.size).append(" @@\n")
        for (line in kept) {
            patch.append('+').append(line).append('\n')
        }
        if (lines.size > MAX_NEW_FILE_LINES) {
            patch.append("+... (")
                .append(lines.size - MAX_NEW_FILE_LINES)
                .append(" more lines omitted)\n")
        }
        if (patch.length > maxLength / 2) {
            return patch.substring(0, maxLength / 2)
        }
        return patch.toString()
    }
}
