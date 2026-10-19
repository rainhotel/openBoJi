package com.boji.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boji.app.model.*
import java.net.URI

@Composable
internal fun SettingsEditor(initial: ModelSettings, actions: AppActions, importedSkills: List<InstalledSkill> = emptyList(), onClose: () -> Unit) {
    var settings by remember { mutableStateOf(initial) }
    var slot by remember { mutableIntStateOf(initial.activeIndex.coerceIn(0, 1)) }
    var connections by remember { mutableStateOf(List(2) { index -> initial.connections.getOrNull(index) ?: ModelConnection(name = if (index == 0) "默认模型" else "备用模型") }) }
    var budget by remember { mutableStateOf(Amounts.yuan(initial.monthlyBudgetFen)) }
    var password by remember { mutableStateOf("") }
    var revealKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var backupError by remember { mutableStateOf<String?>(null) }
    val connection = connections[slot]
    fun update(value: ModelConnection) { connections = connections.mapIndexed { index, old -> if (index == slot) value else old } }
    fun validBackup(): Boolean {
        backupError = if (password.length < 8) "备份密码至少 8 位" else null
        return backupError == null
    }
    EditorFrame("设置", onClose) {
        SectionTitle("AI 助手")
        ToggleRow("使用云端模型", settings.enabled) { settings = settings.copy(enabled = it) }
        ChoiceRow(listOf("默认模型", "备用模型"), slot) { slot = it; revealKey = false }
        FormField("名称", connection.name, { update(connection.copy(name = it)) })
        FormField("接口地址", connection.endpoint, { update(connection.copy(endpoint = it)); error = null }, placeholder = "https://example.com/v1", keyboard = KeyboardType.Uri)
        FormField("模型名称", connection.model, { update(connection.copy(model = it)); error = null }, placeholder = "服务商提供的模型 ID")
        OutlinedTextField(connection.apiKey, { update(connection.copy(apiKey = it)); error = null }, Modifier.fillMaxWidth(), label = { Text("API Key") }, singleLine = true,
            shape = RoundedCornerShape(15.dp), visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = {
                TextButton(onClick = { revealKey = !revealKey }) { Text(if (revealKey) "隐藏" else "显示", fontSize = 12.sp) }
            })
        ToggleRow("支持图片", connection.supportsImages) { update(connection.copy(supportsImages = it)) }
        OutlinedButton(onClick = { settings = settings.copy(activeIndex = slot) }, enabled = settings.activeIndex != slot,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text(if (settings.activeIndex == slot) "当前使用" else "设为当前模型") }
        HorizontalDivider(color = Line)
        SectionTitle("发送范围")
        Text("输入和已选图片会发送给当前模型", color = Muted, fontSize = 12.sp)
        ToggleRow("附带近期记录", settings.includeHistory, "用于结合已有安排做计划") { settings = settings.copy(includeHistory = it) }
        ToggleRow("允许附带收支", settings.includeFinance, "收支记录默认不发送") { settings = settings.copy(includeFinance = it) }
        SectionTitle("记忆与技能")
        OutlinedTextField(settings.memory, { if (it.length <= 4000) settings = settings.copy(memory=it) },
            Modifier.fillMaxWidth(), label = { Text("长期偏好") }, placeholder = { Text("例如：周二、周四晚上训练") },
            minLines = 2, maxLines = 5, shape = RoundedCornerShape(15.dp))
        Text("保存设置后生效；授权近期记录后可供助手读取",color=Muted,fontSize=12.sp)
        Text("内置：每日计划、每周复盘",color=Muted,fontSize=12.sp)
        importedSkills.forEach { skill ->
            Row(Modifier.fillMaxWidth()) {
                Text(skill.name,modifier=Modifier.weight(1f).padding(vertical=14.dp),fontSize=13.sp)
                TextButton(onClick={ actions.removeSkill(skill.id) }) { Text("移除") }
            }
        }
        OutlinedButton(onClick=actions::importSkill,modifier=Modifier.fillMaxWidth()) { Text("导入 Skill（.md）") }
        Text("支持 SKILL.md 指令；脚本和外部文件暂不执行",color=Muted,fontSize=12.sp)
        TextButton(onClick=actions::clearAgentSession) { Text("清空会话记忆") }
        HorizontalDivider(color = Line)
        SectionTitle("预算")
        FormField("每月预算（元）", budget, { budget = it; error = null }, keyboard = KeyboardType.Decimal)
        FormError(error)
        Button(onClick = {
            val amount = runCatching { Amounts.parseFen(budget.trim()) }.getOrNull()
            val active = connections[settings.activeIndex.coerceIn(0, 1)]
            val endpoint = runCatching { URI(active.endpoint.trim()) }.getOrNull()
            error = when {
                amount == null -> "预算金额最多两位小数"
                settings.enabled && (endpoint?.scheme != "https" || endpoint.host.isNullOrBlank()) -> "当前模型需填写 HTTPS 接口地址"
                settings.enabled && active.model.isBlank() -> "填写当前模型名称"
                settings.enabled && active.apiKey.isBlank() -> "填写当前模型 API Key"
                else -> null
            }
            if (error == null) {
                actions.saveSettings(settings.copy(connections = connections.map { it.copy(name = it.name.trim(), endpoint = it.endpoint.trim(), model = it.model.trim(), apiKey = it.apiKey.trim()) }, monthlyBudgetFen = amount ?: 0))
                onClose()
            }
        }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("保存设置") }
        Spacer(Modifier.height(8.dp))
        SectionTitle("本地数据")
        Text("备份需密码；恢复会合并到本机", color = Muted, fontSize = 12.sp)
        OutlinedTextField(password, { password = it; backupError = null }, Modifier.fillMaxWidth(), label = { Text("备份密码（至少 8 位）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(15.dp))
        FormError(backupError)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { if (validBackup()) actions.exportBackup(password) }, Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("加密备份", fontSize = 13.sp) }
            OutlinedButton(onClick = { if (validBackup()) actions.importBackup(password) }, Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("合并恢复", fontSize = 13.sp) }
        }
        OutlinedButton(onClick = actions::exportCsv, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("导出 CSV") }
        Text("CSV 为明文，按需保存和分享", color = Muted, fontSize = 12.sp)
    }
}
