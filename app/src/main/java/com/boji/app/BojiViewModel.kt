package com.boji.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boji.app.assistant.AssistantClient
import com.boji.app.assistant.PiAssistant
import com.boji.app.assistant.AgentWorkspace
import com.boji.app.assistant.DraftLinker
import com.boji.app.assistant.DeepSeekConnection
import com.boji.app.assistant.AssistantFailure
import com.boji.app.reminder.TaskReminders
import com.boji.app.backup.BackupCrypto
import com.boji.app.data.BojiStore
import com.boji.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID

class BojiViewModel @JvmOverloads constructor(application: Application, private val store: BojiStore = BojiStore(application)) : AndroidViewModel(application), AppActions {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState
    private var assistantJob: Job? = null
    private var settingsDraftJob: Job? = null
    private var connectionJob: Job? = null
    private var pi: PiAssistant? = null
    private val agentWorkspace = AgentWorkspace(application)
    private fun agent(): PiAssistant = pi ?: PiAssistant(getApplication(),store,onStatus = { status ->
        mutableState.update { it.copy(assistantStatus=status) }
    }).also { pi=it }
    private val mediaDir = File(application.filesDir, "attachments").apply { mkdirs() }

    init { mutate(null) { expandRecurring(); } }

    fun refresh() { if (!state.value.loading) mutate(null) { expandRecurring() } }
    fun openTask(id: String) { mutableState.update { it.copy(openTaskId=id) } }
    override fun consumeTaskSelection() { mutableState.update { it.copy(openTaskId=null) } }

    private fun mutate(message: String? = "已保存", block: () -> Unit) {
        viewModelScope.launch {
            try {
                mutex.withLock {
                    val snapshot = withContext(Dispatchers.IO) { block(); store.snapshot().also { TaskReminders.rebuild(getApplication(),it.plans) } }
                    updateSnapshot(snapshot, message)
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(loading=false, error=error.message ?: "操作失败，请重试") }
            }
        }
    }

    private fun updateSnapshot(snapshot: AppState, message: String?) {
        mutableState.update { it.copy(
            loading=false, today=LocalDate.now().toString(), records=snapshot.records, plans=snapshot.plans,
            goals=snapshot.goals, settings=snapshot.settings, views=snapshot.views,
            messages=snapshot.messages, draft=snapshot.draft, notice=message, error=null,
            installedSkills=agentWorkspace.installed(),
            memories=snapshot.memories,settingsDraft=store.settingsDraft(),notificationsAllowed=TaskReminders.allowed(getApplication()),
            preciseReminders=TaskReminders.precise(getApplication()),
        ) }
    }

    override fun saveRecord(record: LifeRecord) = mutate { store.saveRecord(record) }
    override fun deleteRecord(id: String) = mutate("已删除") { store.deleteRecord(id) }
    override fun saveGoal(goal: WeeklyGoal) = mutate { store.saveGoal(goal) }
    override fun archiveGoal(id: String) = mutate("已归档") { store.archiveGoal(id) }
    override fun saveSettings(settings: ModelSettings) {
        cancelAssistant()
        val pending=state.value.settingsDraft?.copy(includeHistory=settings.includeHistory,includeFinance=settings.includeFinance,
            includeMemory=settings.includeMemory,rememberConversation=settings.rememberConversation,
            role=settings.role,monthlyBudgetFen=settings.monthlyBudgetFen)
        settingsDraftJob?.cancel()
        mutate {
            store.saveSettings(settings)
            store.saveSettingsDraft(pending)
            agentWorkspace.clearSession()
        }
    }
    override fun saveSettingsDraft(settings: ModelSettings) {
        mutableState.update { it.copy(settingsDraft=settings) }
        settingsDraftJob?.cancel()
        settingsDraftJob=viewModelScope.launch {
            try { delay(200); mutex.withLock { withContext(Dispatchers.IO) { store.saveSettingsDraft(settings) } } }
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){showError("配置暂存失败，请检查输入后重试")}
        }
    }
    override fun saveMemory(memory: MemoryEntry) { cancelAssistant();mutate("已记住") { store.saveMemory(memory); agentWorkspace.clearSession() } }
    override fun deleteMemory(id: String) { cancelAssistant();mutate("已遗忘") { store.deleteMemory(id); agentWorkspace.clearSession() } }
    override fun testConnection(settings: ModelSettings) {
        if(state.value.connectionBusy)return
        cancelAssistant()
        mutableState.update { it.copy(connectionBusy=true,connectionStatus="正在检测官方模型与工具调用…") }
        connectionJob=viewModelScope.launch {
            try {
                val current=settings.connections[settings.activeIndex]
                val models=withContext(Dispatchers.IO) { DeepSeekConnection.models(current.apiKey.trim()) }
                mutableState.update { it.copy(discoveredModels=models) }
                require(models.any { it.model==current.model }) { "当前模型不在官方可用列表，请选择一个可用模型" }
                withTimeout(90_000) { DeepSeekConnection.check(current) }
                settingsDraftJob?.cancel()
                mutex.withLock {
                    val snapshot=withContext(Dispatchers.IO) {
                        store.saveSettings(settings.copy(enabled=true,connections=listOf(current.copy(endpoint=DeepSeekConnection.ENDPOINT,
                            supportsImages=models.find { it.model==current.model }?.supportsImages ?: false)),activeIndex=0))
                        store.saveSettingsDraft(null);agentWorkspace.clearSession();store.snapshot()
                    }
                    updateSnapshot(snapshot,"DeepSeek 已启用")
                }
                mutableState.update { it.copy(connectionStatus="连接成功，对话与工具调用已通过") }
            } catch(_: CancellationException) { mutableState.update { it.copy(connectionStatus="已停止检测，输入已暂存") } }
            catch(failure: AssistantFailure) { mutableState.update { it.copy(connectionStatus=failure.safeMessage) } }
            catch(_: Exception) { mutableState.update { it.copy(connectionStatus="未通过检测，请检查 Key、账户额度和网络后重试") } }
            finally { mutableState.update { it.copy(connectionBusy=false) } }
        }
    }
    override fun cancelConnection() { connectionJob?.cancel() }
    override fun saveView(view: SavedView) = mutate { store.saveView(view) }
    override fun deleteView(name: String) = mutate("视图已删除") { store.deleteView(name) }
    override fun skipPlan(id: String) = mutate("已跳过") { store.skipPlan(id) }

    override fun deletePlan(id: String) = mutate("安排已删除") {
        store.deletePlan(id)
    }

    override fun savePlan(plan: Plan, repeatDays: Set<Int>) = mutate {
        if (repeatDays.isEmpty() || plan.seriesId != null) store.savePlan(plan)
        else {
            require(repeatDays.all { it in 1..7 }) { "请选择有效星期" }
            val rules = rules()
            val id = UUID.randomUUID().toString()
            val rule = JSONObject().put("id",id).put("title",plan.title).put("domain",plan.domain.name)
                .put("date",plan.date).put("time",plan.time).put("minutes",plan.expectedMinutes)
                .put("goalId",plan.goalId).put("days",JSONArray(repeatDays.sorted()))
                .put("template",com.boji.app.data.ModelJson.plan(plan.copy(status=PlanStatus.TODO)))
            val generated = generateRule(rule, store.snapshot().plans.map { it.id }.toSet())
            rules.put(rule)
            store.savePlans(generated,JSONObject().put("rules",rules).put("exceptions",JSONArray(exceptions().toList())).toString())
        }
    }

    private fun rules() = JSONObject(store.recurrenceJson()).getJSONArray("rules")
    private fun exceptions(): Set<String> = JSONObject(store.recurrenceJson()).getJSONArray("exceptions").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    private fun generateRule(rule: JSONObject, known: Set<String>): List<Plan> {
        val first = LocalDate.parse(rule.getString("date"))
        val today = LocalDate.now()
        val start = maxOf(first,today.minusDays(7))
        val days = rule.getJSONArray("days").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
        val excluded = exceptions()
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today.plusWeeks(8)) }
            .filter { it.dayOfWeek.value in days }
            .map { date -> Plan(
                id="${rule.getString("id")}:$date", title=rule.getString("title"),
                domain=Domain.valueOf(rule.getString("domain")), date=date.toString(),
                time=rule.getString("time"), expectedMinutes=rule.getInt("minutes"),
                goalId=rule.optString("goalId").takeIf { it.isNotBlank() && it != "null" }, seriesId=rule.getString("id"),
            ).let { generated -> rule.optJSONObject("template")?.let { source ->
                com.boji.app.data.ModelJson.plan(source).copy(id=generated.id,date=generated.date,seriesId=generated.seriesId,status=PlanStatus.TODO)
            } ?: generated } }.filter { it.id !in known && it.id !in excluded }.toList()
    }
    private fun expandRecurring() {
        val existing = store.snapshot().plans.map { it.id }.toSet()
        val allRules = rules()
        val rows = (0 until allRules.length()).flatMap { generateRule(allRules.getJSONObject(it),existing) }
        if (rows.isNotEmpty()) store.savePlans(rows)
    }

    override fun sendMessage(text: String) {
        val current = state.value
        if (current.assistantBusy) return
        if (current.draft != null) { showError("先保存或放下当前草稿"); return }
        if(current.settings.enabled && !DeepSeekConnection.isOfficial(current.settings.connections.getOrNull(current.settings.activeIndex)?.endpoint.orEmpty())) {
            showError("当前版本请在设置连接 DeepSeek 官方平台");return
        }
        if(text.isBlank() && current.pendingImage!=null && (!current.settings.enabled ||
            current.settings.connections.getOrNull(current.settings.activeIndex)?.supportsImages!=true)) {
            showError("请补一句照片对应的记录，照片会作为本地附件保存");return
        }
        val input = text.trim().ifEmpty { if (current.pendingImage != null) "请整理这张图片" else "" }
        if (input.isBlank()) return
        if (input.length > 6000) { showError("一次输入请少于 6000 字"); return }
        mutableState.update { it.copy(assistantBusy=true,error=null) }
        assistantJob = viewModelScope.launch {
            try {
                val imagePath = current.pendingImage
                mutex.withLock {
                    val snapshot = withContext(Dispatchers.IO) {
                        store.saveMessage(ChatMessage(user=true,text=input + if (imagePath != null) "\n[图片]" else ""))
                        store.snapshot()
                    }
                    updateSnapshot(snapshot,null)
                }
                val imageBytes = withContext(Dispatchers.IO) { imagePath?.let { File(it).readBytes() } }
                val canInspect=state.value.settings.connections.getOrNull(state.value.settings.activeIndex)?.supportsImages == true
                val reply = if (state.value.settings.enabled) agent().respond(input,state.value,if(canInspect)imageBytes else null)
                    else AssistantClient().respond(input,state.value,null)
                ensureActive()
                val batch = reply.draft?.let { draft ->
                    DraftLinker.link(draft,state.value,input,imagePath?.let { File(it).name })
                }
                mutex.withLock {
                    val snapshot = withContext(Dispatchers.IO) {
                        store.saveMessage(ChatMessage(user=false,text=reply.text + if(imagePath!=null && !canInspect) "\n照片作为本地附件保留，未交给模型识图。" else ""))
                        store.savePendingDraft(batch)
                        store.snapshot()
                    }
                    updateSnapshot(snapshot,null)
                }
                if (reply.success && batch?.records?.isNotEmpty() == true) mutableState.update { it.copy(pendingImage=null) }
            } catch (_: CancellationException) {
                mutableState.update { it.copy(notice="已停止，输入保留在对话中") }
            } catch (error: Exception) {
                showError(error.message ?: "处理失败，可以重试")
            } finally { mutableState.update { it.copy(assistantBusy=false,assistantStatus="") } }
        }
    }

    override fun cancelAssistant() { assistantJob?.cancel() }
    override fun clearAgentSession() {
        cancelAssistant()
        mutate("会话记忆已清空") { agentWorkspace.clearSession() }
    }
    override fun removeSkill(id: String) = mutate("技能已移除") {
        require(!state.value.assistantBusy) { "请等本轮助手结束后移除" }
        agentWorkspace.removeSkill(id)
    }

    fun receiveSkill(uri: Uri) {
        viewModelScope.launch {
            try {
                require(!state.value.assistantBusy) { "请等本轮助手结束后导入" }
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { readLimited(it,64*1024).toString(Charsets.UTF_8) }
                }
                val name = agent().validateSkill(text)
                withContext(Dispatchers.IO) { agentWorkspace.installSkill(text,name) }
                mutableState.update { it.copy(notice="已导入 $name",installedSkills=agentWorkspace.installed()) }
            } catch (error: Exception) { showError(error.message ?: "Skill 导入失败") }
        }
    }

    override fun onCleared() { assistantJob?.cancel(); pi?.close(); store.close(); super.onCleared() }
    override fun confirmDraft(batch: DraftBatch) = mutate("已保存") {
        store.applyDraft(batch)
        if(batch.memories.isNotEmpty()) agentWorkspace.clearSession()
    }
    override fun saveDraft(batch: DraftBatch) = mutate(null) { store.savePendingDraft(batch) }
    override fun dismissDraft() = mutate("已放下，原文保留") { store.savePendingDraft(null) }
    override fun removeImage() { mutableState.update { it.copy(pendingImage=null) } }
    override fun consumeVoice() { mutableState.update { it.copy(voiceText=null) } }
    fun receiveVoice(text: String) { mutableState.update { it.copy(voiceText=text) } }
    override fun clearNotice() { mutableState.update { it.copy(notice=null,error=null) } }
    fun showError(text: String) { mutableState.update { it.copy(error=text) } }

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                        readLimited(input,12*1024*1024)
                    }
                    require(bytes.size <= 12*1024*1024) { "图片请小于 12 MB" }
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                    require(options.outWidth > 0 && options.outHeight > 0) { "图片无法读取" }
                    var sample = 1
                    while (maxOf(options.outWidth,options.outHeight)/sample > 1800) sample*=2
                    val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
                        ?: error("图片无法读取")
                    val orientation=runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                    val transform=Matrix().apply { when(orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL->setScale(-1f,1f)
                        ExifInterface.ORIENTATION_ROTATE_180->setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL->setScale(1f,-1f)
                        ExifInterface.ORIENTATION_TRANSPOSE->{setRotate(90f);postScale(-1f,1f)}
                        ExifInterface.ORIENTATION_ROTATE_90->setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE->{setRotate(270f);postScale(-1f,1f)}
                        ExifInterface.ORIENTATION_ROTATE_270->setRotate(270f)
                    } }
                    val oriented=if(transform.isIdentity)bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,transform,true)
                    val jpeg = ByteArrayOutputStream().also { oriented.compress(Bitmap.CompressFormat.JPEG,85,it) }.toByteArray()
                    if(oriented!==bitmap)oriented.recycle()
                    bitmap.recycle()
                    File(mediaDir,"${UUID.randomUUID()}.jpg").apply { writeBytes(jpeg) }
                }
                mutableState.update { it.copy(pendingImage=file.absolutePath,notice="已选照片，补充内容后发送") }
            } catch (error: Exception) { showError(error.message ?: "图片读取失败") }
        }
    }

    fun writeBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = mutex.withLock { store.exportJson() }
                    val data = JSONObject(json)
                    val attachments = JSONObject()
                    val records = data.getJSONArray("records")
                    val referenced = (0 until records.length()).flatMap { index ->
                        val row=records.getJSONObject(index)
                        val legacy=row.optString("sourceId").takeIf { it.startsWith("image:") }?.removePrefix("image:")
                        val attached=row.optJSONArray("attachments")
                        listOfNotNull(legacy)+(attached?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
                    }.toSet()
                    for (name in referenced) {
                        require(name.matches(Regex("[a-zA-Z0-9-]+\\.jpg"))) { "附件名称无效" }
                        val file = File(mediaDir,name)
                        require(file.isFile) { "附件缺失，请检查后重试" }
                        attachments.put(name,Base64.encodeToString(file.readBytes(),Base64.NO_WRAP))
                    }
                    val envelope = JSONObject().put("format",1).put("data",data).put("attachments",attachments)
                    val encrypted = BackupCrypto.encrypt(envelope.toString().toByteArray(),password)
                    getApplication<Application>().contentResolver.openOutputStream(uri,"wt")!!.use { it.write(encrypted) }
                }
                mutableState.update { it.copy(notice="已导出加密备份") }
            } catch (error: Exception) { showError(error.message ?: "备份失败") }
        }
    }

    fun readBackup(uri: Uri, password: String) {
        mutate("已合并恢复") {
            val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { readLimited(it,BackupCrypto.MAX_BYTES) }
            val json = JSONObject(BackupCrypto.decrypt(bytes,password).toString(Charsets.UTF_8))
            require(json.getInt("format") == 1) { "备份版本不支持" }
            val files = json.getJSONObject("attachments")
            val pending = files.keys().asSequence().map { name ->
                require(name.matches(Regex("[a-zA-Z0-9-]+\\.jpg"))) { "附件名称无效" }
                val content = Base64.decode(files.getString(name),Base64.DEFAULT)
                require(content.size <= 12*1024*1024) { "附件过大" }
                val target = File(mediaDir,name)
                require(!target.exists() || target.readBytes().contentEquals(content)) { "附件冲突，未恢复" }
                target to content
            }.toList()
            val restoredData = json.getJSONObject("data")
            val rows = restoredData.getJSONArray("records")
            for (i in 0 until rows.length()) {
                val source = rows.getJSONObject(i).optString("sourceId")
                if (source.startsWith("image:")) {
                    val name = source.removePrefix("image:")
                    require(name.matches(Regex("[a-zA-Z0-9-]+\\.jpg")) && (files.has(name) || File(mediaDir,name).isFile)) { "备份缺少引用的图片" }
                }
                rows.getJSONObject(i).optJSONArray("attachments")?.let { a -> (0 until a.length()).forEach { index ->
                    val name=a.getString(index)
                    require(name.matches(Regex("[a-zA-Z0-9-]+\\.jpg")) && (files.has(name) || File(mediaDir,name).isFile)) { "备份缺少引用的图片" }
                } }
            }
            // Files first; an invalid database import can leave harmless unreferenced media, never half-imported rows.
            pending.forEach { (file,content) -> if (!file.exists()) file.writeBytes(content) }
            store.importJson(restoredData.toString())
        }
    }

    fun writeCsv(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val rows = store.snapshot().records
                    fun cell(s: String): String {
                        val safe = if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) "'$s" else s
                        return "\"${safe.replace("\"","\"\"")}\""
                    }
                    val csv = "\uFEFF日期,领域,内容,分钟,金额,收支类型,类别,备注\r\n" + rows.joinToString("\r\n") {
                        listOf(it.date,it.domain.label,it.title,if (it.domain == Domain.FINANCE) "" else it.minutes.toString(),
                            if (it.domain == Domain.FINANCE) Amounts.yuan(it.amountFen) else "",if (it.domain == Domain.FINANCE) it.moneyKind.label else "",it.category,it.note).joinToString(",",transform=::cell)
                    }
                    getApplication<Application>().contentResolver.openOutputStream(uri,"wt")!!.use { it.write(csv.toByteArray()) }
                }
                mutableState.update { it.copy(notice="已导出 CSV") }
            } catch (error: Exception) { showError(error.message ?: "导出失败") }
        }
    }

    // Activity supplies platform pickers; these remain safe when no activity is attached.
    override fun selectImage() = Unit
    override fun voiceInput() = Unit
    override fun exportBackup(password: String) = Unit
    override fun importBackup(password: String) = Unit
    override fun exportCsv() = Unit

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "文件过大" }
            output.write(buffer,0,count)
        }
        return output.toByteArray()
    }
}
