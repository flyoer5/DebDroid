package com.debdroid.app.debug

import fi.iki.elonen.NanoHTTPD
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.1.10 回归：NanoHTTPD parseBody 的 postData 按 ASCII 解码，POST body 中文
 * 全变 U+FFFD（真机暴露：files/write 中文内容写坏）。HttpBody.readUtf8 按原始
 * 字节 UTF-8 解码修复。本测试起本地 NanoHTTPD 全链路验证。
 */
class HttpBodyTest {

    @Test
    fun `utf8 json body roundtrips through local nanohttpd`() {
        val server = object : NanoHTTPD(0) {
            override fun serve(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                val body = HttpBody.readUtf8(session)
                return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", body)
            }
        }.apply { start(5_000, false) }
        try {
            val payload = """{"path":"/sdcard/中文测试-文件.txt","content":"中文内容第一行\nline2\n"}"""
            val conn = URL("http://127.0.0.1:${server.listeningPort}/files/write").openConnection()
                as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertEquals(payload, resp)
            conn.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `empty body and no content-length are safe`() {
        val server = object : NanoHTTPD(0) {
            override fun serve(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                val body = HttpBody.readUtf8(session)
                return newFixedLengthResponse("len=${body.length}")
            }
        }.apply { start(5_000, false) }
        try {
            val conn = URL("http://127.0.0.1:${server.listeningPort}/x").openConnection()
                as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            val resp = conn.inputStream.bufferedReader().readText()
            assertEquals("len=0", resp)
            conn.disconnect()
        } finally {
            server.stop()
        }
    }
}
