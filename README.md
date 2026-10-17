# OpenCode Commit Message（IntelliJ IDEA 插件）

用本地 [opencode](https://opencode.ai) CLI 自动生成规范的 **Conventional Commits** 提交信息，直接写进 IDEA 的提交信息输入框。

> **命名说明**：UI 上叫 **OpenCode**（品牌名），但真正执行的命令仍写作小写的 `opencode`。
> 插件只在设置里按"可执行文件"这一栏的值为名字去唤起进程，所以你自己编译/改过名字的 CLI 也照样能用。

## 核心行为

| 场景 | 取什么内容交给 opencode |
| --- | --- |
| 在 Commit 工具窗口**勾选了文件** | 只取勾选的那些文件（暂存 + 未暂存 + 未跟踪新文件的快照） |
| **没有勾选任何文件** | 只取暂存区内容（`git diff --cached`） |
| 暂存区为空 | 按设置可回落取未暂存改动（`git diff`），否则提示先 `git add` |

生成入口有下面两个，**点一下就直接生成，没有二级菜单**：

1. **Commit 工具窗口**提交信息输入框右上角的星形按钮（和"最近提交信息"图标同一个位置）；
2. **Local Changes / Changes 视图**右键菜单 → *Generate Commit Message By OpenCode*
   （对选中的文件生成，结果弹窗并自动复制到剪贴板）。

改配置走**生成后弹出的通知气泡**：气泡上有 *Open Settings...* 按钮，直接跳到本插件设置页；
气泡收起后这条通知会留在 Event Log（右下角通知中心）里，按钮照样可点。
当然也可以按老路径 Settings ▸ Tools ▸ OpenCode Commit Message 进去。

## 前置条件

1. 本机已安装 opencode CLI（v1.x）：`opencode --version` 能正常输出即可。
   - 例如 `npm i -g opencode-ai`、`choco install opencode` 或 `brew install sst/tap/opencode`。
2. **配置好模型 key**（没有 key 也可以，见下方第 2 点）：`opencode auth login` 按提示登录/填 API key，
   或在项目/全局的 `opencode.json` 里配好 provider。没配 key 时 opencode 会返回
   `No candidate model has a configured, usable provider key`（HTTP 503），插件会把它原样显示在通知里。
   - 想指定某个模型，除了 `opencode auth login` 之外，也可以在插件设置里用"获取模型列表"按钮挑，
     或直接填 `-m provider/model` 的形式。
   - 没有 key 也能试：opencode 自带若干免登录模型（如 `opencode/deepseek-v4.1-flash-free`），
     终端 `opencode models` 列出来的、带 `-free` 后缀的一般都能直接用。
3. 插件依赖 **Git4Idea**，所以非 Git 仓库会直接提示"当前项目不是 Git 仓库"。

## 安装

**方式一：直接装构建好的包**（最省事）

1. 从 [Releases](https://github.com/yodg2gg/opencode-commit/releases) 页面下载 `opencode-commit-1.0.0.zip`；
2. IDEA → Settings → Plugins → ⚙ → *Install Plugin from Disk…* → 选中该 zip → 重启 IDE。

**方式二：自己从源码构建**

需要 **JDK 17+**。首次构建会下载 IntelliJ IDEA Community 2024.3 依赖（约 700MB）。

```bash
# 构建，产物在 build/distributions/opencode-commit-1.0.0.zip
./gradlew buildPlugin

# （可选）用 IntelliJ Plugin Verifier 做兼容性检查，会额外下载一个用于验证的 IDE
./gradlew verifyPlugin

# 也可以起一个带插件的沙箱 IDE 实际试跑
./gradlew runIde
```

仓库已自带 `gradlew` / `gradlew.bat`，不用额外安装 Gradle，直接跑上面的命令即可。

> **关于 Gradle 分发地址**：`gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl`
> 指向腾讯云镜像，国内拉取很快；如果在海外构建建议换回官方地址，只需把这一行改成
> `distributionUrl=https\://services.gradle.org/distributions/gradle-8.12-bin.zip` 即可。

> 构建状态：已在 Windows 11 + Microsoft OpenJDK 17.0.20.1 + Gradle 8.12 下 `./gradlew buildPlugin` 通过，
> 产物 `opencode-commit-1.0.0.zip` 约 66KB（其中的 `lib/opencode-commit-1.0.0.jar` 约 70KB）。

兼容范围：`since-build 243`，即 IDEA 2024.3 及更新版本（含所有基于该平台的 JetBrains IDE）。

## 使用

1. 打开 **Commit** 工具窗口（`Alt+0`），把要提交的文件勾上；
2. 点提交信息框右上角的星形按钮；
3. 等气泡提示"已写入提交信息输入框"，检查/微调后提交。

要改配置就点气泡上的 *Open Settings...*（报错时尤其方便，气泡上同样有这个按钮）。

不想勾选文件时：先把改动 `git add` 进暂存区，再点按钮即可。

## 设置（Settings → Tools → OpenCode Commit Message）

| 项目 | 默认 | 说明 |
| --- | --- | --- |
| 可执行文件 | `opencode` | 已在 PATH 就保持默认；否则填绝对路径，Windows 例如 `C:/Users/me/AppData/Roaming/npm/opencode.cmd` |
| 模型 | 空 | 对应 `-m/--model`，留空用 opencode 默认模型。右侧 **获取模型列表** 按钮会执行 `opencode models` 弹出带搜索框的候选列表，点选后回填输入框 |
| Agent | 空 | 对应 `--agent` |
| Attach URL | 空 | 填 `http://localhost:4096` 可复用常驻的 `opencode serve`，跳过冷启动 |
| 额外参数 | 空 | 直接追加到 `opencode run`，例如 `--auto` |
| 输出格式 | `json` | `json` 会解析 `--format json` 的原始事件流，结果最干净；`text` 走格式化输出并清洗工具行 |
| 超时（秒） | 180 | 单次调用上限 |
| 语言 | Simplified Chinese | 提交信息语言，可切 `English` |
| subject 长度上限 | 72 | 写入提示词 |
| diff 最大字符数 | 20000 | 超出会截断，避免请求过大 |
| 暂存区为空时回落使用未暂存改动 | 开 | 无勾选且无暂存时的兜底 |
| 附带最近 5 条提交标题 | 关 | 打开后会把 `git log` 标题附给模型，让风格更贴近仓库历史 |
| 提示词模板 | 内置 Conventional Commits 模板 | 支持占位符 `{language}`、`{subject_limit}` |

设置页里还有两个辅助按钮：
- **获取模型列表**：执行 `opencode models`，在输入框下方弹出可搜索的模型候选列表，点选即回填"模型"输入框；
- **验证 OpenCode**：执行 `<可执行文件> --version`，用来确认路径/环境是否正确。

两个按钮都实时读取输入框的当前值，所以改完路径不必先点 Apply 就能验证。

> 拉到的列表是 opencode 本地缓存的。如果你刚在别的机器/环境加了新 key，列表看着不对时，
> 在终端跑一次 `opencode models --refresh` 刷新缓存即可。

## 实际执行的命令

```
cd <项目根目录>
opencode run --format json [--model X] [--agent Y] [--attach URL] [额外参数] \
             --file <临时 diff 文件> "<提示词>"
```

- diff 内容写在临时文件里用 `-f` 附加，避免命令行长度限制；
- 不调用任何 shell，参数逐个传递，路径含空格/中文都安全；
- Windows 下把可执行文件填成 `.cmd/.bat`（npm 安装时只有这种 shim）时，进程会经 cmd.exe 转发——cmd 会把多行提示词从换行处截断、连后面的 `--file` 一起丢掉；插件检测到 `.cmd/.bat` 会自动把提示词压成单行兜底，但更稳妥的做法是直接填真正的 `.exe`；
- 工作目录设为项目根目录，opencode 能读到项目里的 `AGENTS.md` / `opencode.json`；
- 提示词明确要求"不要用任何工具、不要改任何文件"，因此默认不需要 `--auto`。如果你的模型很爱乱调工具导致卡在权限请求，可以在"额外参数"里加 `--auto`（有风险，谨慎）。

## 常见问题

**Q：报 `File not found: You are an expert at writing...`？**
旧版本 bug：`--file` 是 array 参数会吞掉后面的提示词。1.0.0 之后的构建已修复（提示词前置 + `--file=` 等号形式），重新构建安装即可。

**Q：点了按钮很久没反应 / 超时？**
两代原因：旧构建没有关闭 opencode 的 stdin，它会一直等管道输入（已修复）；另外 `opencode run` 每次冷启动较慢，建议终端常驻 `opencode serve`，设置里填 Attach URL = `http://localhost:4096`。

**Q：报 `No candidate model has a configured, usable provider key`？**
opencode 还没配模型 key。终端里跑 `opencode auth login` 配置，或在 opencode.json / 插件设置（模型字段）里指定可用模型。

**Q：点了按钮报"opencode 没有返回可用的提交信息"？**
看通知里附的 exit code / stderr。常见原因：
- 没找到 opencode（设置里改成绝对路径）；
- 模型未登录/额度问题，此时 `opencode run` 会在 stderr 报错；
- 模型不听话输出了别的内容，可以先切"输出格式 = text"看原始输出，或在终端手动跑 `git diff | opencode run "…"` 复现。

**Q：生成很慢？**
默认 `opencode run` 每次都会新起本地服务（含 MCP 冷启动）。建议终端常驻 `opencode serve`，然后在设置里填 Attach URL = `http://localhost:4096`。

**Q：Windows 上中文 diff 乱码？**
插件已强制 UTF-8 读写；如果依然异常，检查 `git config i18n.commitEncoding` 与源码文件编码是否一致。

**Q：仓库里有大文件/生成文件导致 diff 巨大？**
调小"diff 最大字符数"，或者干脆别勾选那些文件。

**Q：为什么按钮上没有下拉菜单/右键菜单？**
IC 2024.3 里提交信息区的按钮是 `ActionButton`，它只有一个点击目标：
非左键事件会在 `checkSkipPressForEvent`（`isMetaDown || button != BUTTON1`）里被丢掉，右键拿不到；
做成下拉组则每次生成都要多点击一次。所以最终是"单击直接生成 + 设置入口放在通知气泡上"。

## 目录结构

```
opencode-commit/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties
├─ LICENSE                                 # MIT
├─ src/main/
│  ├─ kotlin/com/github/opencodecommit/
│  │  ├─ GenerateCommitMessageAction.kt      # 入口 Action（单击即生成）
│  │  ├─ OpenOpenCodeCommitSettingsAction.kt # 通知气泡上的打开设置页按钮
│  │  ├─ DiffCollector.kt                    # 勾选文件 / 暂存区 diff 收集
│  │  ├─ OpenCodeRunner.kt                   # 进程调用
│  │  ├─ CommitMessageCleaner.kt             # 输出清洗
│  │  ├─ JsonTextExtractor.kt                # 极简 JSON（JsonLite/JsonParser），提取文本 part
│  │  ├─ OpenCodeCommitSettings.kt           # 设置持久化
│  │  └─ OpenCodeCommitConfigurable.kt       # 设置页（含获取模型列表）
│  └─ resources/
│     ├─ META-INF/plugin.xml
│     └─ icons/
│        ├─ opencodeCommit.svg               # 亮色主题图标
│        └─ opencodeCommit_dark.svg          # 深色主题图标（按 _dark 约定自动切换）
└─ gradle/wrapper/gradle-wrapper.properties
```

## 许可证

[MIT](./LICENSE) © 2026 yodg2gg

本插件只是本地 opencode CLI 的一层壳，不转发也不保存你的代码，所有 diff 都直接交给本机上的 opencode 进程处理。
