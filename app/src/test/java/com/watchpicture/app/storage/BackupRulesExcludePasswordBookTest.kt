package com.watchpicture.app.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份排除规则契约：密码本 DataStore 文件必须被两套备份规则（API 31+ dataExtractionRules
 * 与 API <31 fullBackupContent）按真实文件名排除。
 *
 * Preferences DataStore 的真实文件名为 `<name>.preferences_pb`（下划线，常量见
 * androidx PreferenceDataStoreFile）；曾因误写为 `.preferences.pb`（点分）导致
 * 排除规则匹配不到任何文件、密码本仍入云备份的静默失效。
 */
class BackupRulesExcludePasswordBookTest {

    private val resXmlDir = File("src/main/res/xml")

    private fun readXml(name: String): String {
        val file = File(resXmlDir, name)
        assertTrue("备份规则文件必须存在: $name", file.isFile)
        return file.readText()
    }

    @Test
    fun `两套备份规则都按真实文件名排除密码本 DataStore`() {
        // 与 PasswordBookRepository 的 preferencesDataStore(name = "watchpicture_password_book")
        // 拼接 preferences_pb 常量后的真实落盘路径保持一致
        val realDataStorePath = "datastore/watchpicture_password_book.preferences_pb"

        val extractionRules = readXml("data_extraction_rules.xml")
        assertTrue(
            "dataExtractionRules 必须按真实文件名排除密码本",
            extractionRules.contains(realDataStorePath)
        )
        val fullBackup = readXml("full_backup_content.xml")
        assertTrue(
            "fullBackupContent 必须按真实文件名排除密码本",
            fullBackup.contains(realDataStorePath)
        )
    }

    @Test
    fun `备份规则不得残留点分扩展名的无效路径`() {
        for (name in listOf("data_extraction_rules.xml", "full_backup_content.xml")) {
            val xml = readXml(name)
            assertFalse(
                "$name 残留无效路径 preferences.pb（真实文件名为 preferences_pb），排除规则会静默失效",
                xml.contains("preferences.pb")
            )
        }
    }
}
