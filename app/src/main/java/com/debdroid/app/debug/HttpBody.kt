package com.debdroid.app.debug

import fi.iki.elonen.NanoHTTPD

/**
 * POST body 读取（v2.1.10）。
 *
 * 真机暴露：NanoHTTPD 2.3.1 的 parseBody 把 postData 按
 * ContentType.getEncoding() 解码，而 Content-Type 头不带 charset 时默认
 * **US-ASCII**——任何不带 `; charset=utf-8` 的客户端（curl）POST 中文，
 * JSON 里非 ASCII 全变 U+FFFD（efbfbd）：files/write 中文内容/路径写坏、
 * session/write 中文命令乱码。已用 jar 级最小复现 + 源码定位确认。
 *
 * 修复：按 Content-Length 直读原始字节、UTF-8 解码（JSON 按 RFC 8259 即 UTF-8，
 * 不依赖客户端 charset 头）；无长度信息时退回 parseBody 旧行为。
 */
internal object HttpBody {

    /** 读 POST body 原始字节并按 UTF-8 解码；无 Content-Length 时退回 parseBody 旧路径。 */
    fun readUtf8(session: NanoHTTPD.IHTTPSession): String {
        val len = contentLength(session)
        if (len > 0) {
            val buf = ByteArray(len)
            var off = 0
            val input = session.inputStream
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) break
                off += n
            }
            return String(buf, 0, off, Charsets.UTF_8)
        }
        // 兜底：无长度信息（如 chunked）时走 NanoHTTPD 自带解析（ASCII 场景行为不变）
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** header 键大小写不敏感取 Content-Length。 */
    private fun contentLength(session: NanoHTTPD.IHTTPSession): Int =
        session.headers.entries.firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value?.trim()?.toIntOrNull() ?: 0
}
