package com.boji.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import com.boji.app.data.ModelJson
import org.json.JSONObject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.boji.app.model.*
import com.boji.app.reminder.TaskReminders
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.io.File

@Composable
internal fun RecordEditor(initial: LifeRecord, goals: List<WeeklyGoal>, plans: List<Plan>,
    onSave: (LifeRecord) -> Unit, onCancel: () -> Unit, onDelete: (() -> Unit)? = null,
    saving:Boolean=false,saveError:String?=null) {
    var domain by rememberSaveable(initial.id) { mutableStateOf(initial.domain) }
    var title by rememberSaveable(initial.id) { mutableStateOf(initial.title) }
    var date by rememberSaveable(initial.id) { mutableStateOf(initial.date) }
    var minutes by rememberSaveable(initial.id) { mutableStateOf(if (initial.minutes > 0) initial.minutes.toString() else "") }
    var amount by rememberSaveable(initial.id) { mutableStateOf(if (initial.amountFen > 0) Amounts.yuan(initial.amountFen) else "") }
    var kind by rememberSaveable(initial.id) { mutableStateOf(initial.moneyKind) }
    var category by rememberSaveable(initial.id) { mutableStateOf(initial.category) }
    var note by rememberSaveable(initial.id) { mutableStateOf(initial.note) }
    var goalId by rememberSaveable(initial.id) { mutableStateOf(initial.goalId) }
    var error by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    var extra by rememberSaveable(initial.id) { mutableStateOf(initial.note.isNotEmpty()) }
    var selectedPlan by rememberSaveable(initial.id) { mutableStateOf(initial.planId) }
    var count by rememberSaveable(initial.id) { mutableStateOf(if(initial.count>0)initial.count.toString() else "") }
    var unit by rememberSaveable(initial.id) { mutableStateOf(initial.countUnit) }
    var completes by rememberSaveable(initial.id) { mutableStateOf(initial.completesPlan) }
    val linkedPlan = plans.firstOrNull { it.id == selectedPlan }
    EditorFrame(if (linkedPlan != null) "完成计划" else if (onDelete != null) "编辑记录" else "新记录", onCancel) {
        ChoiceRow(Domain.entries.map { it.label }, Domain.entries.indexOf(domain)) { index ->
            domain = Domain.entries[index]; goalId = null; selectedPlan=null; error = null
        }
        FormField("内容", title, { title = it; error = null }, placeholder = when (domain) { Domain.TRAINING -> "例如：练拳"; Domain.FINANCE -> "例如：晚饭"; else -> "例如：读书或休息" })
        if (domain == Domain.FINANCE) {
            ChoiceRow(MoneyKind.entries.map { it.label }, MoneyKind.entries.indexOf(kind)) { kind = MoneyKind.entries[it] }
            FormField("金额（元）", amount, { amount = it; error = null }, keyboard = KeyboardType.Decimal)
            FormField("分类", category, { category = it }, placeholder = "例如：餐饮、交通、训练")
        } else {
            FormField("时长（分钟）", minutes, { minutes = it; error = null }, keyboard = KeyboardType.Number, placeholder = "可留空")
            FormField("实际数量",count,{count=it;error=null},keyboard=KeyboardType.Number,placeholder="可留空，按原话记录")
            ChoiceRow(listOf("次","组","个","页","题"),listOf("次","组","个","页","题").indexOf(unit)){unit=listOf("次","组","个","页","题")[it]}
        }
        DateField(date, { date = it; error = null })
        if (domain != Domain.FINANCE) GoalSelector(goals.filter { !it.archived && it.domain == domain }, goalId) { goalId = it }
        val choices=plans.filter { it.domain==domain && (it.date==date || it.id==selectedPlan) && (it.status !in setOf(PlanStatus.ARCHIVED,PlanStatus.SKIPPED) || it.id==selectedPlan) }
        Text("关联当天任务",color=Muted,fontSize=12.sp)
        ChoiceRow(listOf("仅保存记录")+choices.map { "${it.title} ${it.time}" },choices.indexOfFirst { it.id==selectedPlan }+1){index->
            selectedPlan=if(index==0)null else choices[index-1].id
            choices.getOrNull(index-1)?.goalId?.let { goalId=it }
        }
        if(selectedPlan!=null)ToggleRow("完成本次任务",completes,"关闭时只记录部分进展"){completes=it}
        if (initial.source != "手动") Text("来源 · ${initial.source}", color = Muted, fontSize = 12.sp)
        recordPhotoNames(initial).forEach { RecordPhoto(it,220.dp) }
        if (extra) FormField("备注", note, { note = it }, singleLine = false)
        else TextButton(onClick = { extra = true }, contentPadding = PaddingValues(0.dp)) { Text("添加备注", fontSize = 13.sp) }
        FormError(error ?: saveError)
        Button(enabled=!saving,onClick = {
            val parsedMinutes = if (minutes.isBlank()) 0 else minutes.toIntOrNull()
            val parsedAmount = if (domain == Domain.FINANCE) runCatching { Amounts.parseFen(amount.trim()) }.getOrNull() else 0L
            error = when {
                title.trim().isEmpty() -> "写一下记录内容"
                runCatching { LocalDate.parse(date) }.isFailure -> "日期格式为 YYYY-MM-DD"
                domain != Domain.FINANCE && (parsedMinutes == null || parsedMinutes < 0 || parsedMinutes > 1440) -> "时长需在 0–1440 分钟内"
                domain == Domain.FINANCE && (parsedAmount == null || parsedAmount <= 0) -> "请输入正确金额，最多两位小数"
                count.isNotBlank() && (count.toIntOrNull()==null || count.toInt() !in 0..100000) -> "数量应为 0–100000 的整数"
                else -> null
            }
            if (error == null) onSave(initial.copy(domain = domain, title = title.trim(), date = date,
                minutes = if (domain == Domain.FINANCE) 0 else parsedMinutes ?: 0,
                amountFen = parsedAmount ?: 0L, moneyKind = kind, category = category.trim(), note = note.trim(),
                goalId = goalId.takeIf { id -> goals.any { it.id == id && it.domain == domain && !it.archived } && domain != Domain.FINANCE },
                planId = selectedPlan.takeIf { id -> choices.any { it.id==id } },count=if(domain==Domain.FINANCE)0 else count.toIntOrNull() ?: 0,
                countUnit=unit,completesPlan=completes))
        }, modifier=Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text(if(saving)"保存中…" else "保存") }
        onDelete?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("删除记录", color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
internal fun PlanEditor(initial: Plan, goals: List<WeeklyGoal>, records: List<LifeRecord> = emptyList(),onEditRecord: (LifeRecord)->Unit = {},onSave: (Plan, Set<Int>) -> Unit,
    onCancel: () -> Unit, onSkip: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
    saving: Boolean = false, saveError: String? = null) {
    var title by rememberSaveable(initial.id) { mutableStateOf(initial.title) }
    var domain by rememberSaveable(initial.id) { mutableStateOf(initial.domain) }
    var date by rememberSaveable(initial.id) { mutableStateOf(initial.date) }
    var time by rememberSaveable(initial.id) { mutableStateOf(initial.time) }
    var minutes by rememberSaveable(initial.id) { mutableStateOf(initial.expectedMinutes.toString()) }
    var goalId by rememberSaveable(initial.id) { mutableStateOf(initial.goalId) }
    var repeats by rememberSaveable(initial.id,stateSaver=Saver<Set<Int>,ArrayList<Int>>(save={ArrayList(it)},restore={it.toSet()})) { mutableStateOf(emptySet<Int>()) }
    var showRepeat by rememberSaveable(initial.id) { mutableStateOf(false) }
    var deadline by rememberSaveable(initial.id) { mutableStateOf(initial.deadline) }
    var priority by rememberSaveable(initial.id) { mutableIntStateOf(initial.priority) }
    var status by rememberSaveable(initial.id) { mutableStateOf(initial.status) }
    var project by rememberSaveable(initial.id) { mutableStateOf(initial.project) }
    var reminder by rememberSaveable(initial.id) { mutableStateOf(initial.reminderMinutes?.toString().orEmpty()) }
    var error by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    var more by rememberSaveable(initial.id) { mutableStateOf(false) }
    EditorFrame(if (onDelete == null) "加计划" else "编辑计划", onCancel) {
        FormField("要做什么", title, { title = it; error = null }, placeholder = "例如：练拳")
        DateField(date, { date = it; error = null })
        FormField("时间", time, { time = it; error = null }, placeholder = "HH:mm，可留空", keyboard = KeyboardType.Text)
        TextButton(onClick={more=!more}) { Text(if(more)"收起更多选项" else "更多选项") }
        if(more) {
            ChoiceRow(Domain.entries.map { it.label }, Domain.entries.indexOf(domain)) { index ->
                val selected=Domain.entries[index]
                if(records.any { it.planId==initial.id && it.domain!=selected }) error="已有关联记录，不能修改任务类型"
                else if(selected!=domain) { domain=selected;goalId=null;error=null }
            }
            DateField(deadline,{deadline=it;error=null},label="截止时间")
            Text("优先级",color=Muted,fontSize=12.sp);ChoiceRow(listOf("P0","P1","P2","P3"),priority){priority=it}
            FormField("项目",project,{project=it},placeholder="例如：期末英语，可留空")
            Text("状态",color=Muted,fontSize=12.sp)
            val states=listOf(PlanStatus.TODO,PlanStatus.IN_PROGRESS,PlanStatus.DONE,PlanStatus.SKIPPED,PlanStatus.ARCHIVED)
            ChoiceRow(states.map { it.label },states.indexOf(status)){status=states[it]}
            FormField("提前提醒（分钟）",reminder,{reminder=it;error=null},placeholder="留空不提醒，0 表示准时",keyboard=KeyboardType.Number)
            if (domain != Domain.FINANCE) FormField("预计时长（分钟）", minutes, { minutes = it; error = null }, keyboard = KeyboardType.Number)
            if (domain != Domain.FINANCE) GoalSelector(goals.filter { !it.archived && it.domain == domain }, goalId) { goalId = it }
            if (onDelete == null) {
                ToggleRow("每周重复", showRepeat) { showRepeat = it; if (!it) repeats = emptySet() }
                if (showRepeat) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                            FilterChip(selected = index + 1 in repeats, onClick = { repeats = if (index + 1 in repeats) repeats - (index + 1) else repeats + (index + 1) },
                                modifier = Modifier.weight(1f), label = { Text(label, fontSize = 12.sp) })
                        }
                    }
                }
            } else if (initial.seriesId != null) Text("只修改本次安排", color = Muted, fontSize = 12.sp)
        }
        FormError(error ?: saveError)
        Button(enabled=!saving,onClick = {
            val count = if(minutes.isBlank())0 else minutes.toIntOrNull()
            error = when {
                title.isBlank() -> "写一下计划内容"
                records.any { it.planId==initial.id && it.domain!=domain } -> "已有关联记录，不能修改任务类型"
                runCatching { LocalDate.parse(date) }.isFailure -> "日期格式为 YYYY-MM-DD"
                time.isNotBlank() && (!time.matches(Regex("[0-2][0-9]:[0-5][0-9]")) || runCatching { LocalTime.parse(time) }.isFailure) -> "时间格式为 HH:mm"
                domain != Domain.FINANCE && (count == null || count < 0 || count > 1440) -> "预计时长需在 0–1440 分钟内"
                showRepeat && repeats.isEmpty() -> "选择重复的星期"
                deadline.isNotBlank() && runCatching { java.time.LocalDateTime.parse(deadline) }.isFailure && runCatching { LocalDate.parse(deadline) }.isFailure -> "截止时间格式不正确"
                reminder.isNotBlank() && (reminder.toIntOrNull()==null || reminder.toInt() !in 0..10080 || time.isBlank()) -> "提醒需要明确时间和有效提前量"
                status==PlanStatus.DONE && records.none { it.planId==initial.id && it.completesPlan } -> "完成任务请先记录实际内容"
                status !in setOf(PlanStatus.DONE,PlanStatus.ARCHIVED) && records.any { it.planId==initial.id && it.completesPlan } -> "已有完成记录，请先修改记录的完成状态"
                else -> null
            }
            if (error == null) onSave(initial.copy(title = title.trim(), domain = domain, date = date, time = time.trim(),
                expectedMinutes = if (domain == Domain.FINANCE) 0 else count ?: 25,
                goalId = goalId.takeIf { id -> goals.any { it.id == id && it.domain == domain && !it.archived } && domain != Domain.FINANCE },
                deadline=deadline.trim(),priority=priority,status=status,project=project.trim(),reminderMinutes=reminder.toIntOrNull()), repeats)
        }, modifier=Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text(if(saving)"保存中…" else "保存计划") }
        if (onSkip != null || onDelete != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            onSkip?.let { TextButton(onClick = it) { Text("跳过本次") } }
            onDelete?.let { TextButton(onClick = it) { Text("删除本次", color = MaterialTheme.colorScheme.error) } }
        }
        val linkedRecords=records.filter { it.planId==initial.id }
        if(linkedRecords.isNotEmpty()) {
            SectionTitle("实际记录与照片")
            linkedRecords.forEach { record -> RecordRow(record,goals,showDate=true,onClick={onEditRecord(record)}) }
        }
    }
}

@Composable
internal fun GoalEditor(initial: WeeklyGoal, onSave: (WeeklyGoal) -> Unit, onCancel: () -> Unit, onArchive: (() -> Unit)? = null) {
    var title by rememberSaveable(initial.id) { mutableStateOf(initial.title) }
    var domain by rememberSaveable(initial.id) { mutableStateOf(initial.domain) }
    var mode by rememberSaveable(initial.id) { mutableStateOf(initial.mode) }
    var target by rememberSaveable(initial.id) { mutableStateOf(initial.target.toString()) }
    var minimum by rememberSaveable(initial.id) { mutableStateOf(if (initial.minimumMinutes > 0) initial.minimumMinutes.toString() else "") }
    var error by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    val domains = listOf(Domain.TRAINING, Domain.LEARNING)
    EditorFrame(if (onArchive == null) "每周目标" else "编辑目标", onCancel) {
        ChoiceRow(domains.map { it.label }, domains.indexOf(domain)) { domain = domains[it] }
        FormField("目标", title, { title = it; error = null }, placeholder = "例如：每周练拳")
        ChoiceRow(GoalMode.entries.map { it.label }, GoalMode.entries.indexOf(mode)) { mode = GoalMode.entries[it] }
        FormField(if (mode == GoalMode.COUNT) "每周几次" else "每周几分钟", target, { target = it; error = null }, keyboard = KeyboardType.Number)
        if (mode == GoalMode.COUNT) {
            FormField("每次至少几分钟", minimum, { minimum = it; error = null }, keyboard = KeyboardType.Number, placeholder = "可留空")
            Text("同一天算一次；关联记录后自动累计", color = Muted, fontSize = 12.sp)
        } else Text("关联记录后，实际时长自动累计", color = Muted, fontSize = 12.sp)
        FormError(error)
        Button(onClick = {
            val value = target.toIntOrNull()
            val min = if (minimum.isBlank()) 0 else minimum.toIntOrNull()
            error = when {
                title.isBlank() -> "写一下目标内容"
                value == null || value <= 0 || (mode == GoalMode.COUNT && value > 7) || value > 10080 -> if (mode == GoalMode.COUNT) "次数需在 1–7 之间" else "分钟数需在 1–10080 之间"
                mode == GoalMode.COUNT && (min == null || min < 0 || min > 1440) -> "时长需在 0–1440 分钟内"
                else -> null
            }
            if (error == null) onSave(initial.copy(title = title.trim(), domain = domain, mode = mode, target = value ?: 3, minimumMinutes = min ?: 0))
        }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("保存目标") }
        onArchive?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("归档目标") } }
    }
}

@Composable
internal fun DraftEditor(initial: DraftBatch, goals: List<WeeklyGoal>, plans: List<Plan>, notificationsAllowed:Boolean=false,onChange: (DraftBatch)->Unit = {},onConfirm: (DraftBatch) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(initial.id,stateSaver=Saver<DraftBatch,String>(save={ModelJson.draft(it).toString()},restore={ModelJson.draft(JSONObject(it))})) { mutableStateOf(initial) }
    var editRecord by rememberSaveable(stateSaver=Saver<LifeRecord?,String>(save={it?.let { v->ModelJson.record(v).toString() } ?: ""},restore={it.takeIf { v->v.isNotEmpty() }?.let { v->ModelJson.record(JSONObject(v)) }})) { mutableStateOf<LifeRecord?>(null) }
    var editPlan by rememberSaveable(stateSaver=Saver<Plan?,String>(save={it?.let { v->ModelJson.plan(v).toString() } ?: ""},restore={it.takeIf { v->v.isNotEmpty() }?.let { v->ModelJson.plan(JSONObject(v)) }})) { mutableStateOf<Plan?>(null) }
    LaunchedEffect(draft) { if(draft!=initial)onChange(draft) }
    if (editRecord != null) {
        RecordEditor(editRecord!!, goals, plans+draft.plans, onSave = { changed -> draft = draft.copy(records = draft.records.map { if (it.id == changed.id) changed else it }); editRecord = null },
            onCancel = { editRecord = null }, onDelete = { draft = draft.copy(records = draft.records.filterNot { it.id == editRecord!!.id }); editRecord = null })
    } else if (editPlan != null) {
        PlanEditor(editPlan!!, goals, onSave = { changed, _ -> draft = draft.copy(plans = draft.plans.map { if (it.id == changed.id) changed else it }); editPlan = null },
            onCancel = { editPlan = null }, onDelete = { draft = draft.copy(plans = draft.plans.filterNot { it.id == editPlan!!.id }); editPlan = null })
    } else EditorFrame("核对后保存", onDismiss) {
        TaskQuery.conflicts(draft.plans,plans).forEach { Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp) }
        draft.memories.forEach { memory -> Text("记住：${memory.text}",modifier=Modifier.padding(vertical=8.dp)) }
        Text("点开可修改，确认前不会写入", color = Muted, fontSize = 13.sp)
        if (draft.records.isNotEmpty()) SectionTitle("记录", "${draft.records.size} 条")
        draft.records.forEach { record -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            RecordRow(record, goals, showDate = true) { editRecord = record }
            val targets=plans+draft.plans
            val linked=targets.find {it.id==record.planId}
            val photos=recordPhotoNames(record)
            if(linked!=null || targets.any {it.domain==record.domain && it.date==record.date && it.status !in setOf(PlanStatus.SKIPPED,PlanStatus.ARCHIVED)})
                Text(if(linked==null)"未关联任务，点开记录可选择" else
                    "${if(record.completesPlan)"保存后完成" else "仅记录进展"}：${linked.title}",fontSize=13.sp,color=Muted)
            if(photos.isNotEmpty()) {Text("${photos.size} 张照片",fontSize=12.sp,color=Muted);RecordPhoto(photos.first(),100.dp)}
        } }
        if (draft.plans.isNotEmpty()) SectionTitle("计划", "${draft.plans.size} 个")
        draft.plans.forEach { plan -> Column {
            PlanRow(plan, onClick = { editPlan = plan }, onComplete = { editPlan = plan }, showDate = true)
            plan.reminderMinutes?.let { minutes ->
                val at=TaskReminders.triggerAt(plan)
                val status=when {at==null || at<=System.currentTimeMillis()->"提醒时间已过，请调整";!notificationsAllowed->"需开启系统通知";else->"确认后启用"}
                Text("提前 $minutes 分钟提醒 · $status",fontSize=13.sp,color=Muted)
            }
        } }
        Button(onClick = { onConfirm(draft) }, enabled = draft.records.isNotEmpty() || draft.plans.isNotEmpty() || draft.memories.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("确认保存") }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("放弃草稿") }
    }
}

private fun recordPhotoNames(record:LifeRecord)=(record.attachments+listOfNotNull(record.sourceId?.takeIf {it.startsWith("image:")}?.removePrefix("image:"))).distinct()
@Composable private fun RecordPhoto(filename:String,maxHeight:Dp) {
    val context=LocalContext.current
    val picture=remember(filename) {filename.takeIf {it.matches(Regex("[a-zA-Z0-9-]+\\.jpg"))}?.let {
        BitmapFactory.decodeFile(File(context.filesDir,"attachments/$it").absolutePath,BitmapFactory.Options().apply {inSampleSize=4})
    }}
    if(picture!=null)Image(picture.asImageBitmap(),contentDescription="记录照片",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxWidth().heightIn(max=maxHeight))
}

@Composable
internal fun EditorFrame(title: String, onCancel: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding().verticalScroll(rememberScrollState())
        .padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onCancel) { Glyph("close", description = "关闭") }
        }
        content()
    }
}

@Composable internal fun FormField(label: String, value: String, onChange: (String) -> Unit,
    placeholder: String = "", singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text) {
    val requester=remember { BringIntoViewRequester() };var focused by remember { mutableStateOf(false) }
    val focus=LocalFocusManager.current;val controller=LocalSoftwareKeyboardController.current
    val ime=WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused,ime) { if(focused)requester.bringIntoView() }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().bringIntoViewRequester(requester).onFocusChanged { focused=it.isFocused }, label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder, fontSize = 14.sp) }) else null,
        singleLine = singleLine, minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 7,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard,imeAction=if(singleLine)ImeAction.Next else ImeAction.Default),
        keyboardActions=KeyboardActions(onNext={focus.moveFocus(FocusDirection.Down)},onDone={controller?.hide();focus.clearFocus()}),shape = RoundedCornerShape(15.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DateField(value: String, onChange: (String) -> Unit, label: String = "日期") {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        shape = RoundedCornerShape(15.dp), trailingIcon = { IconButton(onClick = { open = true }) { Glyph("calendar", description = "选择日期") } })
    if (open) {
        val day = runCatching { LocalDate.parse(value.substringBefore('T')) }.getOrDefault(LocalDate.now())
        val picker = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = { TextButton(onClick = {
            picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() + if(value.contains('T'))"T"+value.substringAfter('T') else "") }; open = false
        }) { Text("确定") } }, dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } }) { DatePicker(picker) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun GoalSelector(goals: List<WeeklyGoal>, selected: String?, onSelect: (String?) -> Unit) {
    if (goals.isNotEmpty()) {
        Text("计入目标", color = Muted, fontSize = 12.sp)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=selected==null,onClick={onSelect(null)},label={Text("不关联")})
            goals.forEach { goal -> FilterChip(selected=goal.id==selected,onClick={onSelect(goal.id)},label={Text(goal.title)}) }
        }
    }
}
@Composable internal fun FormError(error: String?) { if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
@Composable internal fun ToggleRow(title: String, checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, fontSize = 15.sp)
            detail?.let { Text(it, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp)) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
