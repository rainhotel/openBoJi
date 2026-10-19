package com.boji.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boji.app.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.io.File

@Composable
internal fun RecordEditor(initial: LifeRecord, goals: List<WeeklyGoal>, plans: List<Plan>,
    onSave: (LifeRecord) -> Unit, onCancel: () -> Unit, onDelete: (() -> Unit)? = null) {
    var domain by remember(initial.id) { mutableStateOf(initial.domain) }
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var date by remember(initial.id) { mutableStateOf(initial.date) }
    var minutes by remember(initial.id) { mutableStateOf(if (initial.minutes > 0) initial.minutes.toString() else "") }
    var amount by remember(initial.id) { mutableStateOf(if (initial.amountFen > 0) Amounts.yuan(initial.amountFen) else "") }
    var kind by remember(initial.id) { mutableStateOf(initial.moneyKind) }
    var category by remember(initial.id) { mutableStateOf(initial.category) }
    var note by remember(initial.id) { mutableStateOf(initial.note) }
    var goalId by remember(initial.id) { mutableStateOf(initial.goalId) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var extra by remember(initial.id) { mutableStateOf(initial.note.isNotEmpty()) }
    val linkedPlan = plans.firstOrNull { it.id == initial.planId }
    EditorFrame(if (linkedPlan != null) "完成计划" else if (onDelete != null) "编辑记录" else "新记录", onCancel) {
        ChoiceRow(Domain.entries.map { it.label }, Domain.entries.indexOf(domain)) { index ->
            domain = Domain.entries[index]; goalId = null; error = null
        }
        FormField("内容", title, { title = it; error = null }, placeholder = when (domain) { Domain.TRAINING -> "例如：练拳"; Domain.FINANCE -> "例如：晚饭"; Domain.LEARNING -> "例如：读书" })
        if (domain == Domain.FINANCE) {
            ChoiceRow(MoneyKind.entries.map { it.label }, MoneyKind.entries.indexOf(kind)) { kind = MoneyKind.entries[it] }
            FormField("金额（元）", amount, { amount = it; error = null }, keyboard = KeyboardType.Decimal)
            FormField("分类", category, { category = it }, placeholder = "例如：餐饮、交通、训练")
        } else FormField("时长（分钟）", minutes, { minutes = it; error = null }, keyboard = KeyboardType.Number, placeholder = "可留空")
        DateField(date, { date = it; error = null })
        if (domain != Domain.FINANCE) GoalSelector(goals.filter { !it.archived && it.domain == domain }, goalId) { goalId = it }
        if (linkedPlan != null && domain == initial.domain) Text("关联计划 · ${linkedPlan.title}", color = Muted, fontSize = 12.sp)
        if (initial.source != "手动") Text("来源 · ${initial.source}", color = Muted, fontSize = 12.sp)
        val context = LocalContext.current
        val picture = remember(initial.sourceId) {
            initial.sourceId?.removePrefix("image:")?.takeIf { initial.sourceId.startsWith("image:") && it.matches(Regex("[a-zA-Z0-9-]+\\.jpg")) }
                ?.let { BitmapFactory.decodeFile(File(context.filesDir,"attachments/$it").absolutePath) }
        }
        if (picture != null) Image(picture.asImageBitmap(),contentDescription="原始图片",contentScale=ContentScale.Fit,
            modifier=Modifier.fillMaxWidth().heightIn(max=180.dp))
        if (extra) FormField("备注", note, { note = it }, singleLine = false)
        else TextButton(onClick = { extra = true }, contentPadding = PaddingValues(0.dp)) { Text("添加备注", fontSize = 13.sp) }
        FormError(error)
        Button(onClick = {
            val parsedMinutes = if (minutes.isBlank()) 0 else minutes.toIntOrNull()
            val parsedAmount = if (domain == Domain.FINANCE) runCatching { Amounts.parseFen(amount.trim()) }.getOrNull() else 0L
            error = when {
                title.trim().isEmpty() -> "写一下记录内容"
                runCatching { LocalDate.parse(date) }.isFailure -> "日期格式为 YYYY-MM-DD"
                domain != Domain.FINANCE && (parsedMinutes == null || parsedMinutes < 0 || parsedMinutes > 1440) -> "时长需在 0–1440 分钟内"
                domain == Domain.FINANCE && (parsedAmount == null || parsedAmount <= 0) -> "请输入正确金额，最多两位小数"
                else -> null
            }
            if (error == null) onSave(initial.copy(domain = domain, title = title.trim(), date = date,
                minutes = if (domain == Domain.FINANCE) 0 else parsedMinutes ?: 0,
                amountFen = parsedAmount ?: 0L, moneyKind = kind, category = category.trim(), note = note.trim(),
                goalId = goalId.takeIf { id -> goals.any { it.id == id && it.domain == domain && !it.archived } && domain != Domain.FINANCE },
                planId = initial.planId.takeIf { domain == initial.domain }))
        }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("保存") }
        onDelete?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("删除记录", color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
internal fun PlanEditor(initial: Plan, goals: List<WeeklyGoal>, onSave: (Plan, Set<Int>) -> Unit,
    onCancel: () -> Unit, onSkip: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var domain by remember(initial.id) { mutableStateOf(initial.domain) }
    var date by remember(initial.id) { mutableStateOf(initial.date) }
    var time by remember(initial.id) { mutableStateOf(initial.time) }
    var minutes by remember(initial.id) { mutableStateOf(initial.expectedMinutes.toString()) }
    var goalId by remember(initial.id) { mutableStateOf(initial.goalId) }
    var repeats by remember(initial.id) { mutableStateOf(emptySet<Int>()) }
    var showRepeat by remember(initial.id) { mutableStateOf(false) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    EditorFrame(if (onDelete == null) "加计划" else "编辑计划", onCancel) {
        ChoiceRow(Domain.entries.map { it.label }, Domain.entries.indexOf(domain)) { domain = Domain.entries[it]; goalId = null }
        FormField("要做什么", title, { title = it; error = null }, placeholder = "例如：练拳")
        DateField(date, { date = it; error = null })
        FormField("时间", time, { time = it; error = null }, placeholder = "HH:mm，可留空", keyboard = KeyboardType.Text)
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
        FormError(error)
        Button(onClick = {
            val count = minutes.toIntOrNull()
            error = when {
                title.isBlank() -> "写一下计划内容"
                runCatching { LocalDate.parse(date) }.isFailure -> "日期格式为 YYYY-MM-DD"
                time.isNotBlank() && (!time.matches(Regex("[0-2][0-9]:[0-5][0-9]")) || runCatching { LocalTime.parse(time) }.isFailure) -> "时间格式为 HH:mm"
                domain != Domain.FINANCE && (count == null || count <= 0 || count > 1440) -> "预计时长需在 1–1440 分钟内"
                showRepeat && repeats.isEmpty() -> "选择重复的星期"
                else -> null
            }
            if (error == null) onSave(initial.copy(title = title.trim(), domain = domain, date = date, time = time.trim(),
                expectedMinutes = if (domain == Domain.FINANCE) 0 else count ?: 25,
                goalId = goalId.takeIf { id -> goals.any { it.id == id && it.domain == domain && !it.archived } && domain != Domain.FINANCE }), repeats)
        }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("保存计划") }
        if (onSkip != null || onDelete != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            onSkip?.let { TextButton(onClick = it) { Text("跳过本次") } }
            onDelete?.let { TextButton(onClick = it) { Text("删除本次", color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
internal fun GoalEditor(initial: WeeklyGoal, onSave: (WeeklyGoal) -> Unit, onCancel: () -> Unit, onArchive: (() -> Unit)? = null) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var domain by remember(initial.id) { mutableStateOf(initial.domain) }
    var mode by remember(initial.id) { mutableStateOf(initial.mode) }
    var target by remember(initial.id) { mutableStateOf(initial.target.toString()) }
    var minimum by remember(initial.id) { mutableStateOf(if (initial.minimumMinutes > 0) initial.minimumMinutes.toString() else "") }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
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
internal fun DraftEditor(initial: DraftBatch, goals: List<WeeklyGoal>, plans: List<Plan>, onConfirm: (DraftBatch) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var editRecord by remember { mutableStateOf<LifeRecord?>(null) }
    var editPlan by remember { mutableStateOf<Plan?>(null) }
    if (editRecord != null) {
        RecordEditor(editRecord!!, goals, plans, onSave = { changed -> draft = draft.copy(records = draft.records.map { if (it.id == changed.id) changed else it }); editRecord = null },
            onCancel = { editRecord = null }, onDelete = { draft = draft.copy(records = draft.records.filterNot { it.id == editRecord!!.id }); editRecord = null })
    } else if (editPlan != null) {
        PlanEditor(editPlan!!, goals, onSave = { changed, _ -> draft = draft.copy(plans = draft.plans.map { if (it.id == changed.id) changed else it }); editPlan = null },
            onCancel = { editPlan = null }, onDelete = { draft = draft.copy(plans = draft.plans.filterNot { it.id == editPlan!!.id }); editPlan = null })
    } else EditorFrame("核对后保存", onDismiss) {
        Text("点开可修改，确认前不会写入", color = Muted, fontSize = 13.sp)
        if (draft.records.isNotEmpty()) SectionTitle("记录", "${draft.records.size} 条")
        draft.records.forEach { record -> RecordRow(record, goals, showDate = true) { editRecord = record } }
        if (draft.plans.isNotEmpty()) SectionTitle("计划", "${draft.plans.size} 个")
        draft.plans.forEach { plan -> PlanRow(plan, onClick = { editPlan = plan }, onComplete = { editPlan = plan }, showDate = true) }
        Button(onClick = { onConfirm(draft) }, enabled = draft.records.isNotEmpty() || draft.plans.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("确认保存") }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("放弃草稿") }
    }
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
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder, fontSize = 14.sp) }) else null,
        singleLine = singleLine, minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 7,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), shape = RoundedCornerShape(15.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DateField(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text("日期") }, singleLine = true,
        shape = RoundedCornerShape(15.dp), trailingIcon = { IconButton(onClick = { open = true }) { Glyph("calendar", description = "选择日期") } })
    if (open) {
        val day = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        val picker = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = { TextButton(onClick = {
            picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; open = false
        }) { Text("确定") } }, dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } }) { DatePicker(picker) }
    }
}

@Composable private fun GoalSelector(goals: List<WeeklyGoal>, selected: String?, onSelect: (String?) -> Unit) {
    if (goals.isNotEmpty()) {
        Text("计入目标", color = Muted, fontSize = 12.sp)
        ChoiceRow(listOf("不关联") + goals.map { it.title }, (goals.indexOfFirst { it.id == selected } + 1).coerceAtLeast(0)) { onSelect(if (it == 0) null else goals[it - 1].id) }
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
