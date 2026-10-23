package com.github.opencodecommitmessage

/**
 * 极简 JSON 解析器 + 提取器。
 *
 * opencode --format json 会往 stdout 打印一串 NDJSON 事件，例如：
 * {"type":"message.part.updated","properties":{"part":{"id":"prt_1","type":"text","text":"feat: ..."}}}
 *
 * 这里不引入第三方依赖，自己扫一遍即可；同一个 part 可能出现多次（增量/快照），
 * 按 part id 去重并保留最长的一份文本。
 */
internal object JsonTextExtractor {

    fun extractText(jsonOutput: String): String {
        if (jsonOutput.isBlank()) return ""
        val texts = LinkedHashMap<String, String>()
        var cursor = 0
        while (cursor < jsonOutput.length) {
            val start = jsonOutput.indexOf('{', cursor)
            if (start < 0) break
            val (value, end) = JsonLite.parseAt(jsonOutput, start)
            cursor = if (end > start) end else start + 1
            if (value is Map<*, *>) collect(value, texts)
        }
        return texts.values.joinToString("\n")
    }

    /**
     * 从 json 事件流里提取 API 错误信息，例如：
     * {"type":"error","error":{"name":"APIError","data":{"message":"No candidate model..."}}}
     */
    fun extractError(jsonOutput: String): String? {
        if (jsonOutput.isBlank()) return null
        var cursor = 0
        while (cursor < jsonOutput.length) {
            val start = jsonOutput.indexOf('{', cursor)
            if (start < 0) break
            val (value, end) = JsonLite.parseAt(jsonOutput, start)
            cursor = if (end > start) end else start + 1
            if (value is Map<*, *> && value["type"] == "error") {
                val error = value["error"]
                if (error is Map<*, *>) {
                    val data = error["data"]
                    val message = (data as? Map<*, *>)?.get("message") as? String
                        ?: error["message"] as? String
                    if (!message.isNullOrBlank()) return message
                }
            }
        }
        return null
    }

    private fun collect(node: Map<*, *>, out: LinkedHashMap<String, String>) {
        val type = node["type"] as? String
        val text = node["text"] as? String
        // 只认 type=="text" 且确实带正文的节点，避免把 reasoning / 工具事件混进来
        if (type == "text" && !text.isNullOrBlank()) {
            val key = (node["id"] as? String) ?: "text_${out.size}"
            val existing = out[key]
            if (existing == null || text.length > existing.length) out[key] = text
        }
        // 注意：opencode 的 text 事件是 {"type":"text", "part":{"type":"text","text":...}}，
        // 顶层只有 type 没有正文，因此绝不能在这里提前 return，必须继续深入子节点。
        for (child in node.values) {
            when (child) {
                is Map<*, *> -> collect(child, out)
                is List<*> -> child.filterIsInstance<Map<*, *>>().forEach { collect(it, out) }
            }
        }
    }
}

internal object JsonLite {

    /** 从 start 位置解析一个 JSON 值，返回 (值, 结束下标)。解析失败时结束下标为 -1。 */
    fun parseAt(text: String, start: Int): Pair<Any?, Int> {
        val parser = JsonParser(text, start)
        return Pair(parser.readValue(), parser.position)
    }
}

private class JsonParser(private val text: String, position: Int) {

    var position: Int = position
        private set

    fun readValue(): Any? {
        skipWhitespace()
        if (position >= text.length) return null
        when (text[position]) {
            '{' -> return readObject()
            '[' -> return readArray()
            '"' -> return readString()
            't' -> { skip(4); return true }
            'f' -> { skip(5); return false }
            'n' -> { skip(4); return null }
        }
        return readNumber()
    }

    private fun readObject(): Map<String, Any?> {
        position++ // '{'
        val map = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (position < text.length && text[position] == '}') {
            position++
            return map
        }
        while (position < text.length) {
            skipWhitespace()
            if (position >= text.length || text[position] != '"') break
            val key = readString() ?: break
            skipWhitespace()
            if (position < text.length && text[position] == ':') position++
            map[key] = readValue()
            skipWhitespace()
            if (position < text.length && text[position] == ',') {
                position++
                continue
            }
            break
        }
        skipWhitespace()
        if (position < text.length && text[position] == '}') position++
        return map
    }

    private fun readArray(): List<Any?> {
        position++ // '['
        val list = ArrayList<Any?>()
        skipWhitespace()
        if (position < text.length && text[position] == ']') {
            position++
            return list
        }
        while (position < text.length) {
            list.add(readValue())
            skipWhitespace()
            if (position < text.length && text[position] == ',') {
                position++
                continue
            }
            break
        }
        skipWhitespace()
        if (position < text.length && text[position] == ']') position++
        return list
    }

    private fun readString(): String? {
        skipWhitespace()
        if (position >= text.length || text[position] != '"') return null
        position++
        val builder = StringBuilder()
        while (position < text.length) {
            when (val c = text[position]) {
                '"' -> {
                    position++
                    return builder.toString()
                }
                '\\' -> {
                    position++
                    if (position >= text.length) break
                    when (val escaped = text[position]) {
                        'n' -> builder.append('\n')
                        't' -> builder.append('\t')
                        'r' -> builder.append('\r')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'u' -> {
                            if (position + 4 < text.length) {
                                val code = text.substring(position + 1, position + 5).toIntOrNull(16)
                                if (code != null) builder.append(code.toChar())
                                position += 4
                            }
                        }
                        else -> builder.append(escaped)
                    }
                    position++
                }
                else -> {
                    builder.append(c)
                    position++
                }
            }
        }
        return builder.toString()
    }

    private fun readNumber(): Any? {
        val start = position
        if (position < text.length && (text[position] == '-' || text[position] == '+')) position++
        while (position < text.length) {
            val c = text[position]
            if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') position++ else break
        }
        val raw = text.substring(start, position)
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()
    }

    private fun skipWhitespace() {
        while (position < text.length && text[position].isWhitespace()) position++
    }

    private fun skip(count: Int) {
        position = (position + count).coerceAtMost(text.length)
    }
}
