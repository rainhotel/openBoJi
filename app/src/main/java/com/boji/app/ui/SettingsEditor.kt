package com.boji.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boji.app.model.*
import com.boji.app.assistant.DeepSeekConnection

@Composable
internal fun SettingsEditor(state: AppState, actions: AppActions, onClose: () -> Unit) {
    val initial=remember { state.settingsDraft ?: state.settings }
    var settings by remember { mutableStateOf(initial) }
    val saved=initial.connections.getOrNull(initial.activeIndex)?.takeIf { DeepSeekConnection.isOfficial(it.endpoint) }
    var key by remember { mutableStateOf(saved?.apiKey.orEmpty()) }
    var model by remember { mutableStateOf(saved?.model?.takeIf { it.startsWith("deepseek-") } ?: DeepSeekConnection.DEFAULT_MODEL) }
    var thinking by remember { mutableStateOf(saved?.thinking ?: false) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var reveal by remember { mutableStateOf(false) }
    var budget by rememberSaveable { mutableStateOf(Amounts.yuan(initial.monthlyBudgetFen)) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val models=(state.discoveredModels.map { it.model } + listOf(DeepSeekConnection.DEFAULT_MODEL,"deepseek-v4-pro",model)).distinct()
    fun candidate(): ModelSettings {
        val images=state.discoveredModels.find { it.model==model }?.supportsImages ?: saved?.takeIf { it.model==model }?.supportsImages ?: false
        return settings.copy(activeIndex=0,connections=listOf(DeepSeekConnection.default(key.trim()).copy(model=model,thinking=thinking,supportsImages=images)))
    }
    LaunchedEffect(key,model,thinking,settings) { actions.saveSettingsDraft(candidate()) }
    fun leave() { actions.cancelConnection();onClose() }
    BackHandler { leave() }
    Scaffold(containerColor=androidx.compose.ui.graphics.Color.White,
        topBar={ Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal=12.dp)) {
            TextButton(onClick=::leave,modifier=Modifier.height(56.dp).testTag("settings-back")) { Text("返回") }
            Text("设置",fontSize=22.sp,modifier=Modifier.padding(14.dp))
        } },
        bottomBar={ Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(20.dp)) {
            FormError(error ?: state.error)
            if(section==0) {
                state.connectionStatus?.let { Text(it,color=Muted,fontSize=13.sp,modifier=Modifier.padding(bottom=10.dp)) }
                Button(onClick={
                    error=if(key.isBlank())"请先粘贴 API Key" else null
                    if(error==null) actions.testConnection(candidate().copy(enabled=true))
                },enabled=!state.connectionBusy,modifier=Modifier.fillMaxWidth().height(52.dp).testTag("connect-deepseek"),shape=RoundedCornerShape(18.dp)) {
                    Text(if(state.connectionBusy)"正在检测…" else "检测并使用 DeepSeek")
                }
            } else Button(onClick={
                val amount=runCatching { Amounts.parseFen(budget) }.getOrNull()
                error=if(amount==null)"预算最多两位小数" else null
                if(error==null) actions.saveSettings(state.settings.copy(includeHistory=settings.includeHistory,includeFinance=settings.includeFinance,
                    includeMemory=settings.includeMemory,rememberConversation=settings.rememberConversation,monthlyBudgetFen=amount!!))
            },modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(18.dp)) { Text("保存设置") }
            Text("模型配置会自动暂存",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
            .padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            ChoiceRow(listOf("模型","使用范围","数据与技能"),section) { section=it }
            if(section==0) {
                SectionTitle("DeepSeek 官方")
                Text("粘贴密钥，检测通过后即可使用",color=Muted,fontSize=14.sp)
                OutlinedTextField(key,{key=it;error=null},Modifier.fillMaxWidth().testTag("deepseek-key"),label={Text("API Key")},singleLine=true,
                    enabled=!state.connectionBusy,shape=RoundedCornerShape(16.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),
                    visualTransformation=if(reveal)VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon={TextButton(onClick={reveal=!reveal}) {Text(if(reveal)"隐藏" else "显示")}})
                Text("从 DeepSeek 开放平台创建 API Key",color=Muted,fontSize=12.sp)
                ChoiceRow(models,models.indexOf(model).coerceAtLeast(0)) { model=models[it] }
                ToggleRow("深入思考",thinking,"处理复杂规划时使用") { thinking=it }
                Text("接口地址和能力由应用适配；照片也可仅作为本地附件保存。",fontSize=13.sp,color=Muted)
                OutlinedButton(onClick={actions.saveSettings(state.settings.copy(enabled=false));onClose()},modifier=Modifier.fillMaxWidth()) { Text("暂用本地记录") }
            } else if(section==1) {
                SectionTitle("助手可以读取")
                ToggleRow("近期任务和记录",settings.includeHistory,"用于避开已有安排") { settings=settings.copy(includeHistory=it) }
                ToggleRow("收支记录",settings.includeFinance,"单独控制收支信息") { settings=settings.copy(includeFinance=it) }
                ToggleRow("长期记忆",settings.includeMemory,"用于参考偏好和时间限制") { settings=settings.copy(includeMemory=it) }
                ToggleRow("继续最近对话",settings.rememberConversation,"理解前文，清空会话可重新开始") { settings=settings.copy(rememberConversation=it) }
                TextButton(onClick=actions::clearAgentSession) { Text("清空会话，保留长期记忆") }
                SectionTitle("日程提醒")
                Text(if(state.notificationsAllowed)"系统通知已开启" else "系统通知未开启，日程暂不能提醒",color=Muted,fontSize=13.sp)
                OutlinedButton(onClick=actions::requestNotifications) { Text("开启通知权限") }
                if(!state.preciseReminders) {
                    Text("系统可能延后提醒，允许闹钟和提醒可提高准时性。",color=Muted,fontSize=12.sp)
                    OutlinedButton(onClick=actions::requestPreciseReminders){Text("允许准时提醒")}
                }
            } else {
                SectionTitle("预算")
                FormField("每月预算（元）",budget,{budget=it},keyboard=KeyboardType.Decimal)
                SectionTitle("本地数据")
                OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("备份密码（至少 8 位）")},singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),shape=RoundedCornerShape(16.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={if(password.length>=8)actions.exportBackup(password) else error="密码至少 8 位"},modifier=Modifier.weight(1f)) { Text("加密备份") }
                    OutlinedButton(onClick={if(password.length>=8)actions.importBackup(password) else error="密码至少 8 位"},modifier=Modifier.weight(1f)) { Text("合并恢复") }
                }
                OutlinedButton(onClick=actions::exportCsv,modifier=Modifier.fillMaxWidth()) { Text("导出 CSV") }
                SectionTitle("技能")
                Text("内置每日计划、每周复盘",fontSize=13.sp,color=Muted)
                state.installedSkills.forEach { skill -> Row { Text(skill.name,Modifier.weight(1f).padding(top=12.dp));TextButton(onClick={actions.removeSkill(skill.id)}){Text("移除")} } }
                OutlinedButton(onClick=actions::importSkill,modifier=Modifier.fillMaxWidth()) { Text("导入 Skill（.md）") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
