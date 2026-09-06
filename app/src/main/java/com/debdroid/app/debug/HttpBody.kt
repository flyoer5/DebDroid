package com.debdroid.app.debug

import fi.iki.elonen.IHTTPSession

/**
 * POST body 读取（v2.1.10）。
 *
 * 真机暴露：NanoHTTPD 2.3. 的 parseBody 把 postData 按 ASCII 解码——JSON body 里的
 * 非 ASCII（中文路径/内容/命令参数）全部变 U+FFFD 替换符（efbfbd），files/write
 * 中文内容直接写坏。改为按 Content-Length 读原始字节、UTF-8 解码。
 */
internal object HttpBody {

    /** 读 POST body 原始字节并按 UTF-8 解码；无 Content-Length 时退回 parseBody 旧路径。 */
    fun readUtf8(session: IHTTPSession): String {
        val len = contentLength(session)
        if (len > 0) {
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = session.inputStream.read(buf, off, len - off)
                if (n < 0) break
                off += n
            }
            return String(buf, 0, off, Charsets.UTF_8)
        }
        // 兜底：无长度信息（如 chunked）时走 NanoHTTPD 自带解析（ASCII 场景行为不变）
        val files = HashMap<String, String>()
        return runCatching { session.parseBody(files) }.getOrNull()
            ?.get("postData") ?: ""
    }

    /** header 键大小写不敏感取 Content-Length。 */
    private fun contentLength(session: IHTTPSession): Int =
        session.headers.entries.firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value?.trim()?.toIntOrNull() ?: 0
}
