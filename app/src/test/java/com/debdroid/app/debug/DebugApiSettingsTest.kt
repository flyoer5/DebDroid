package com.debdroid.app.debug

import com.debdroid.app.prefs.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DebugApiServer 纯逻辑单测：PUT /api/settings 部分更新。 */
class DebugApiSettingsTest {

    @Test
    fun `applyJson updates only present fields`() {
        val base = AppSettings(sshPort = 8022, sshEnabled = false, fontSize = 14)
        val updated = base.applyJson(JSONObject("""{"sshPort": 9022, "debugApiEnabled": true}"""))

        assertEquals(9022, updated.sshPort)
        assertTrue(updated.debugApiEnabled)
        // 未出现的字段保持不变
        assertFalse(updated.sshEnabled)
        assertEquals(14, updated.fontSize)
    }

    @Test
    fun `applyJson clamps port to range`() {
        val base = AppSettings(sshPort = 8022)
        assertEquals(1024, base.applyJson(JSONObject("""{"sshPort": 1}""")).sshPort)
        assertEquals(65535, base.applyJson(JSONObject("""{"sshPort": 99999}""")).sshPort)
    }

    @Test
    fun `applyJson falls back on invalid values`() {
        val base = AppSettings(sshPort = 8022, themeMode = com.debdroid.app.prefs.ThemeMode.DARK)
        val updated = base.applyJson(JSONObject("""{"themeMode": "BOGUS"}"""))
        assertEquals(com.debdroid.app.prefs.ThemeMode.DARK, updated.themeMode)
    }

    @Test
    fun `applyJson maps apt mirror id through enum`() {
        val base = AppSettings()
        val updated = base.applyJson(JSONObject("""{"aptMirrorId": "tuna"}"""))
        assertEquals("tuna", updated.aptMirrorId)
        // 非法 id 回退官方
        val bad = base.applyJson(JSONObject("""{"aptMirrorId": "nope"}"""))
        assertEquals("official", bad.aptMirrorId)
    }

    @Test
    fun `applyJson empty object keeps everything`() {
        val base = AppSettings(sshPort = 8022, fontSize = 20)
        assertEquals(base, base.applyJson(JSONObject("{}")))
    }
}
