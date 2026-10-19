package com.boji.app.assistant

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Platform storage for Pi transcripts and Agent Skills. No model credentials enter this directory. */
internal class AgentWorkspace(private val context: Context, directory: String = "agent") {
    private val root = File(context.filesDir, directory).apply { mkdirs() }
    private val session = AtomicFile(File(root, "session.json"))
    private val skillsDir = File(root, "skills").apply { mkdirs() }

    @Synchronized fun skills(): JSONObject {
        val files = JSONObject()
        for (name in context.assets.list("skills").orEmpty()) {
            context.assets.open("skills/$name/SKILL.md").bufferedReader().use { files.put("/skills/$name/SKILL.md",it.readText()) }
        }
        skillsDir.listFiles()?.filter { it.isFile && it.extension == "md" }?.sortedBy { it.name }?.forEach {
            files.put("/skills/${it.nameWithoutExtension}/SKILL.md",it.readText())
        }
        return files
    }

    @Synchronized fun installSkill(text: String, name: String): String {
        require(text.toByteArray().size <= 64 * 1024) { "Skill 请小于 64 KB" }
        require(installed().none { it.name == name } && name !in setOf("daily-plan","weekly-review")) { "此名称的技能已存在，请先移除" }
        require(skillsDir.listFiles().orEmpty().count { it.extension == "md" } < 30) { "最多导入 30 个 Skill" }
        val id = UUID.randomUUID().toString()
        File(skillsDir,"$id.md").writeText(text)
        File(skillsDir,"$id.title").writeText(name)
        return id
    }

    @Synchronized fun removeSkill(id: String) {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        File(skillsDir,"$id.md").delete()
        File(skillsDir,"$id.title").delete()
    }

    @Synchronized fun installed(): List<com.boji.app.model.InstalledSkill> = skillsDir.listFiles().orEmpty()
        .filter { it.extension == "md" }.map {
            com.boji.app.model.InstalledSkill(it.nameWithoutExtension,File(skillsDir,"${it.nameWithoutExtension}.title").takeIf(File::isFile)?.readText() ?: "导入的 Skill")
        }

    @Synchronized fun history(scope: String): JSONArray = runCatching {
        val root = JSONObject(session.openRead().bufferedReader().use { it.readText() })
        if (root.getString("scope") == scope) root.getJSONArray("messages") else JSONArray()
    }.getOrElse { JSONArray() }

    @Synchronized fun saveHistory(scope: String, messages: JSONArray) {
        // Keep whole user-turn groups so tool results never lose their tool calls.
        var start = 0
        val userStarts = (0 until messages.length()).filter { messages.getJSONObject(it).optString("role") == "user" }
        if (userStarts.size > 8) start = userStarts[userStarts.size - 8]
        fun retained() = JSONArray().also { out -> (start until messages.length()).forEach { out.put(messages.get(it)) } }
        var kept = retained()
        while (kept.toString().toByteArray().size > 160 * 1024 && start < messages.length()) {
            start = userStarts.firstOrNull { it > start } ?: messages.length()
            kept = retained()
        }
        val bytes = JSONObject().put("scope",scope).put("messages",kept).toString().toByteArray()
        val output = session.startWrite()
        try { output.write(bytes); session.finishWrite(output) }
        catch (error: Exception) { session.failWrite(output); throw error }
    }

    @Synchronized fun clearSession() { session.delete() }
}
