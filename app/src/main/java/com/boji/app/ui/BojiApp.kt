package com.boji.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import com.boji.app.data.ModelJson
import org.json.JSONObject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boji.app.model.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val Ink = Color(0xFF202020)
internal val Muted = Color(0xFF686868)
internal val Soft = Color(0xFFF4F4F4)
internal val Line = Color(0xFFE9E9E9)
private val WeekNames = listOf("一", "二", "三", "四", "五", "六", "日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BojiApp(state: AppState, actions: AppActions) {
    val colors = lightColorScheme(
        primary = Ink, onPrimary = Color.White, primaryContainer = Color(0xFFEAEAEA),
        onPrimaryContainer = Ink, secondary = Muted, onSecondary = Color.White,
        secondaryContainer = Color(0xFFEAEAEA), onSecondaryContainer = Ink,
        tertiary = Muted, tertiaryContainer = Soft, onTertiaryContainer = Ink,
        background = Color.White, onBackground = Ink, surface = Color.White,
        onSurface = Ink, surfaceVariant = Soft, onSurfaceVariant = Muted,
        surfaceContainerHighest=Soft,surfaceContainerHigh=Soft,surfaceContainer=Soft,surfaceContainerLow=Color.White,surfaceTint=Ink,
        outline = Color(0xFF9A9A9A), outlineVariant = Line,
    )
    MaterialTheme(colorScheme = colors) {
        val compact=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val pageStates=rememberSaveableStateHolder()
        var sheet by rememberSaveable { mutableStateOf<String?>(null) }
        var record by rememberSaveable(stateSaver=Saver<LifeRecord,String>(save={ModelJson.record(it).toString()},restore={ModelJson.record(JSONObject(it))})) { mutableStateOf(LifeRecord()) }
        var plan by rememberSaveable(stateSaver=Saver<Plan,String>(save={ModelJson.plan(it).toString()},restore={ModelJson.plan(JSONObject(it))})) { mutableStateOf(Plan()) }
        var goal by rememberSaveable(stateSaver=Saver<WeeklyGoal,String>(save={ModelJson.goal(it).toString()},restore={ModelJson.goal(JSONObject(it))})) { mutableStateOf(WeeklyGoal()) }
        var deletion by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
        var waitingSave by rememberSaveable {mutableStateOf<String?>(null)}
        var taskDate by rememberSaveable {mutableStateOf<String?>(null)}
        val snackbar = remember { SnackbarHostState() }
        val today = LocalDate.parse(state.today)
        fun openRecord(value: LifeRecord) { waitingSave=null;record = value; sheet = "record" }
        fun openPlan(value: Plan) { waitingSave=null;plan = value; sheet = "plan" }
        LaunchedEffect(state.savedItemId,state.saving,waitingSave) {
            val sameEditor=(sheet=="plan" && plan.id==waitingSave) || (sheet=="record" && record.id==waitingSave) || (sheet=="draft" && state.draft==null)
            if(waitingSave!=null && state.savedItemId==waitingSave && !state.saving && sameEditor) {sheet=null;waitingSave=null}
        }
        LaunchedEffect(state.openTaskId,state.loading) {
            if(!state.loading && state.openTaskId!=null) {
                state.plans.find { it.id==state.openTaskId }?.let { openPlan(it) }
                actions.consumeTaskSelection()
            }
        }
        fun completePlan(value: Plan) {
            openRecord(LifeRecord(title = value.title, domain = value.domain,
                date = today.toString(), minutes = value.expectedMinutes,
                goalId = value.goalId, planId = value.id))
        }
        // Restore encrypted settings before an editor can autosave its initial value.
        if(state.loading) { Box(Modifier.fillMaxSize().background(Color.White),contentAlignment=Alignment.Center) { CircularProgressIndicator() };return@MaterialTheme }
        if(sheet=="settings") { SettingsEditor(state,actions,onClose={sheet=null});return@MaterialTheme }
        if(sheet=="memory") { MemoryScreen(state,actions,onClose={sheet=null});return@MaterialTheme }
        LaunchedEffect(state.error, state.notice) {
            val message = state.error ?: state.notice
            if (message != null) {
                snackbar.showSnackbar(message, withDismissAction = true)
                actions.clearNotice()
            }
        }
        Scaffold(
            containerColor = Color.White,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(if(compact)48.dp else 64.dp).padding(horizontal = 22.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.size(30.dp).background(Ink, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Glyph("mark", Color.White, Modifier.size(19.dp))
                        }
                        Text("薄肌", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row {
                        TextButton(onClick={sheet="memory"}) { Text("记忆",fontSize=13.sp) }
                        IconButton(onClick = { sheet = "settings" }) { Glyph("settings", description = "设置") }
                    }
                }
            },
            bottomBar = {
                Column(Modifier.background(Color.White).navigationBarsPadding()) {
                    HorizontalDivider(color = Line)
                    Row(Modifier.fillMaxWidth().height(if(compact)48.dp else 72.dp)) {
                        listOf("助手" to "chat", "日程" to "calendar", "积累" to "grid").forEachIndexed { index, item ->
                            Column(Modifier.weight(1f).fillMaxHeight().clickable { tab = index },
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(width = 56.dp, height = if(compact)22.dp else 30.dp)
                                    .background(if (tab == index) Soft else Color.Transparent, RoundedCornerShape(16.dp)),
                                    contentAlignment = Alignment.Center) { Glyph(item.second, if (tab == index) Ink else Muted) }
                                Text(item.first, color = if (tab == index) Ink else Muted, fontSize = 12.sp,
                                    fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (tab == 2 && !state.loading) {
                    ExtendedFloatingActionButton(onClick = { openRecord(LifeRecord()) }, modifier = Modifier.testTag("record-fab"),
                        containerColor = Ink, contentColor = Color.White,
                        shape = RoundedCornerShape(24.dp),
                        icon = { Glyph("plus", Color.White) }, text = { Text("记录", fontWeight = FontWeight.Medium) })
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Ink)
                else AnimatedContent(targetState = tab, label = "页面") { selected ->
                    pageStates.SaveableStateProvider(selected) {
                    when (selected) {
                        0 -> AssistantPage(state, actions, { sheet = "draft" }, { sheet = "settings" }, {date ->taskDate=date;tab=1})
                        1 -> TaskWorkspace(state,actions,::openRecord,::openPlan,::completePlan,requestedDate=taskDate,onDateHandled={taskDate=null})
                        else -> SpacePage(state, actions, ::openRecord, { goal = WeeklyGoal(); sheet = "goal" },
                            { goal = it; sheet = "goal" })
                    }
                    }
                }
            }
        }
        if (sheet != null) {
            ModalBottomSheet(onDismissRequest = { sheet = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,confirmValueChange={it!=SheetValue.Hidden}),
                containerColor = Color.White, dragHandle = { BottomSheetDefaults.DragHandle(color = Line) }) {
                when (sheet) {
                    "record" -> RecordEditor(record, state.goals, state.plans,saving=state.saving,saveError=state.saveError.takeIf {waitingSave==record.id},
                        onSave = { waitingSave=it.id;actions.saveRecord(it) },
                        onCancel = { sheet = null },
                        onDelete = if (state.records.any { it.id == record.id }) ({
                            deletion = "删除这条记录？" to { actions.deleteRecord(record.id); sheet = null }
                        }) else null)
                    "plan" -> PlanEditor(plan, state.goals,records=state.records,onEditRecord=::openRecord,saving=state.saving,saveError=state.saveError.takeIf {waitingSave==plan.id},
                        onSave = { value, repeats -> waitingSave=value.id;actions.savePlan(value, repeats) },
                        onCancel = { sheet = null },
                        onSkip = if (state.plans.any { it.id == plan.id } && plan.status == PlanStatus.TODO) ({ actions.skipPlan(plan.id); sheet = null }) else null,
                        onDelete = if (state.plans.any { it.id == plan.id }) ({
                            deletion = "删除这次计划？" to { actions.deletePlan(plan.id); sheet = null }
                        }) else null)
                    "goal" -> GoalEditor(goal, onSave = { actions.saveGoal(it); sheet = null }, onCancel = { sheet = null },
                        onArchive = if (state.goals.any { it.id == goal.id }) ({ actions.archiveGoal(goal.id); sheet = null }) else null)
                    "draft" -> state.draft?.let { batch -> DraftEditor(batch, state.goals, state.plans,notificationsAllowed=state.notificationsAllowed,onChange=actions::saveDraft,
                        onConfirm = { waitingSave=it.id;actions.confirmDraft(it) }, onDismiss = { actions.dismissDraft(); sheet = null }) }
                }
            }
        }
        deletion?.let { target ->
            AlertDialog(onDismissRequest = { deletion = null }, title = { Text(target.first) },
                text = { Text("相关目标进度将重新计算。") },
                confirmButton = { TextButton(onClick = { target.second(); deletion = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
        }
    }
}

@Composable
private fun TodayPage(state: AppState, record: (LifeRecord) -> Unit, plan: (Plan) -> Unit,
    complete: (Plan) -> Unit, addGoal: () -> Unit, editGoal: (WeeklyGoal) -> Unit) {
    val today = LocalDate.parse(state.today)
    val plans = state.plans.filter { it.date <= today.toString() && it.status == PlanStatus.TODO }.sortedWith(compareBy({ it.date }, { it.time }))
    val records = state.records.filter { it.date == today.toString() }.sortedByDescending { it.createdAt }
    val goals = state.goals.filter { !it.archived }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(today.format(DateTimeFormatter.ofPattern("M月d日 · 星期E", Locale.CHINA)).replace("星期周", "星期"),
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
            Text("今日", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp, bottom = 18.dp))
        }
        item { SectionTitle("待完成", "加计划") { plan(Plan()) } }
        if (plans.isEmpty()) item { Text("暂无计划",color=Muted,fontSize=14.sp,modifier=Modifier.padding(vertical=14.dp)) }
        items(plans, key = { "p${it.id}" }) { PlanRow(it, onClick = { plan(it) }, onComplete = { complete(it) }, showDate = it.date < today.toString()) }
        item { Spacer(Modifier.height(18.dp)); SectionTitle("本周目标", "添加", addGoal) }
        if (goals.isEmpty()) item { Text("暂无目标",color=Muted,fontSize=14.sp,modifier=Modifier.padding(vertical=14.dp)) }
        items(goals, key = { "g${it.id}" }) { GoalRow(it, state.records, today) { editGoal(it) } }
        item { Spacer(Modifier.height(18.dp)); SectionTitle("今日记录", "${records.size} 条") }
        if (records.isEmpty()) item { Text("暂无记录",color=Muted,fontSize=14.sp,modifier=Modifier.padding(vertical=14.dp)) }
        items(records, key = { "r${it.id}" }) { RecordRow(it, state.goals) { record(it) } }
    }
}

@Composable
internal fun CalendarPage(state: AppState, record: (LifeRecord) -> Unit, plan: (Plan) -> Unit, complete: (Plan) -> Unit,showTitle: Boolean=true,
    selectedDate:String=state.today,onSelectDate:(String)->Unit={}) {
    val selected=selectedDate
    var monthText by rememberSaveable { mutableStateOf(selected.take(7)) }
    var expandedMonth by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(selectedDate) {monthText=selectedDate.take(7)}
    val month = YearMonth.parse(monthText)
    val date = LocalDate.parse(selected)
    val plans = state.plans.filter { it.date == selected }
    val records = state.records.filter { it.date == selected }.sortedByDescending { it.createdAt }
    val eventDates = (state.records.map { it.date } + state.plans.map { it.date }).toSet()
    LazyColumn(Modifier.fillMaxSize().testTag("task-calendar"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if(showTitle)item { PageTitle("日历") }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${month.year}年 ${month.monthValue}月", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onSelectDate(state.today); monthText = YearMonth.parse(state.today.take(7)).toString() }) { Text("今天") }
                TextButton(onClick={expandedMonth=!expandedMonth}) {Text(if(expandedMonth)"收起" else "月历",fontSize=12.sp)}
                IconButton(onClick = { monthText = month.minusMonths(1).toString();expandedMonth=true }) { Glyph("left", description = "上个月") }
                IconButton(onClick = { monthText = month.plusMonths(1).toString();expandedMonth=true }) { Glyph("right", description = "下个月") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { WeekNames.forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Muted, fontSize = 12.sp) } }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val cells = ((offset + month.lengthOfMonth() + 6) / 7) * 7
            repeat(if(expandedMonth)cells / 7 else 1) { week ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { weekday ->
                        val number = week * 7 + weekday - offset + 1
                        Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                            if (!expandedMonth || number in 1..month.lengthOfMonth()) {
                                val d = if(expandedMonth)month.atDay(number) else Progress.weekStart(date).plusDays(weekday.toLong())
                                val active = d == date
                                Column(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(if (active) Ink else if (d == LocalDate.now()) Soft else Color.Transparent)
                                    .clickable { onSelectDate(d.toString()) }.semantics { contentDescription = "$d${if (eventDates.contains(d.toString())) " 有记录或计划" else ""}" },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(d.dayOfMonth.toString(), color = if (active) Color.White else Ink, fontSize = 15.sp)
                                    Box(Modifier.padding(top = 2.dp).size(3.dp).background(if (eventDates.contains(d.toString())) { if (active) Color.White else Ink } else Color.Transparent, CircleShape))
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 18.dp, bottom = 14.dp), color = Line)
        }
        item { SectionTitle("${date.monthValue}月${date.dayOfMonth}日", "加计划") { plan(Plan(date = selected)) } }
        if (plans.isEmpty() && records.isEmpty()) item { EmptyLine("这天还没有安排", "添加记录") { record(LifeRecord(date = selected)) } }
        items(plans, key = { "p${it.id}" }) { PlanRow(it, { plan(it) }, { complete(it) }) }
        if (records.isNotEmpty()) item { Text("实际记录", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 3.dp)) }
        items(records, key = { "r${it.id}" }) { RecordRow(it, state.goals) { record(it) } }
    }
}

@Composable
private fun SpacePage(state: AppState, actions: AppActions, editRecord: (LifeRecord) -> Unit, addGoal: () -> Unit, editGoal: (WeeklyGoal) -> Unit) {
    var domainName by rememberSaveable { mutableStateOf("") }
    var week by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var table by rememberSaveable { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var viewName by remember { mutableStateOf("") }
    var viewError by remember { mutableStateOf<String?>(null) }
    val domain = Domain.entries.firstOrNull { it.name == domainName }
    val today = LocalDate.parse(state.today)
    val start = Progress.weekStart(today)
    val rows = state.records.filter { row -> (domain == null || row.domain == domain) &&
        (!week || (row.date >= start.toString() && row.date < start.plusDays(7).toString())) &&
        (query.isBlank() || listOf(row.title, row.note, row.category).any { it.contains(query, ignoreCase = true) }) }.sortedWith(compareByDescending<LifeRecord> { it.date }.thenByDescending { it.createdAt })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageTitle("空间") }
        item {
            ChoiceRow(listOf("全部") + Domain.entries.map { it.label }, (listOf<Domain?>(null) + Domain.entries).indexOf(domain)) { index -> domainName = if (index == 0) "" else Domain.entries[index - 1].name }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索记录") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), leadingIcon = { Glyph("search", Muted) })
        }
        if (state.views.isNotEmpty()) item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.views.filter { it.scope=="records" }.forEach { view ->
                    InputChip(selected = domain == view.domain && week == view.thisWeek,
                        onClick = { domainName = view.domain?.name.orEmpty(); week = view.thisWeek },
                        label = { Text(view.name) }, trailingIcon = {
                            Box(Modifier.size(36.dp).clickable { actions.deleteView(view.name) }, contentAlignment = Alignment.Center) { Glyph("close", Muted, Modifier.size(14.dp), "删除视图 ${view.name}") }
                        })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = week, onClick = { week = !week }, label = { Text("本周") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { naming = true }) { Text("存为视图", fontSize = 13.sp) }
                IconButton(onClick = { table = !table }) { Glyph(if (table) "list" else "table", description = if (table) "切换列表" else "切换表格") }
            }
        }
        item {
            val finances = rows.filter { it.domain == Domain.FINANCE }
            val minutes = rows.filter { it.domain != Domain.FINANCE }.sumOf { it.minutes }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Metric("记录", "${rows.size}")
                if (domain != Domain.FINANCE) Metric("累计分钟", "$minutes")
                if (domain == Domain.FINANCE || domain == null) Metric("净支出", "¥${Amounts.yuan(Progress.netExpense(finances))}")
            }
            if (domain == Domain.FINANCE) {
                val month = today.toString().take(7)
                val spent = Progress.netExpense(state.records.filter { it.date.startsWith(month) })
                val left = state.settings.monthlyBudgetFen - spent
                Text(if (state.settings.monthlyBudgetFen > 0) "本月预算 ¥${Amounts.yuan(state.settings.monthlyBudgetFen)} · ${if (left >= 0) "剩余" else "超出"} ¥${Amounts.yuan(kotlin.math.abs(left))}" else "月预算未设置", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Line)
        }
        item { SectionTitle("记录", if (table) "表格" else "列表") }
        if (table && rows.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 9.dp)) {
                Text("日期", Modifier.width(48.dp), color = Muted, fontSize = 12.sp)
                Text("内容", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
                Text("数值", Modifier.width(85.dp), color = Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        if (rows.isEmpty()) item { EmptyLine(if (query.isNotBlank() || week) "没有符合条件的记录" else "还没有记录", "记一笔") { editRecord(LifeRecord(domain = domain ?: Domain.TRAINING)) } }
        items(rows, key = { it.id }) { row ->
            if (table) Row(Modifier.fillMaxWidth().clickable { editRecord(row) }.heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.date.takeLast(5), Modifier.width(48.dp), fontSize = 12.sp, color = Muted)
                Column(Modifier.weight(1f)) { Text(row.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(row.domain.label, fontSize = 11.sp, color = Muted) }
                Text(recordValue(row), Modifier.width(85.dp), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            } else RecordRow(row, state.goals, showDate = true) { editRecord(row) }
        }
        item { Spacer(Modifier.height(20.dp)); SectionTitle("每周目标", "添加", addGoal) }
        items(state.goals.filter { !it.archived && (domain == null || it.domain == domain) }, key = { "goal${it.id}" }) { goal -> GoalRow(goal, state.records, today) { editGoal(goal) } }
    }
    if (naming) AlertDialog(onDismissRequest = { naming = false }, title = { Text("保存视图") }, text = {
        Column {
            OutlinedTextField(viewName, { viewName = it; viewError = null }, label = { Text("名称") }, singleLine = true, isError = viewError != null)
            viewError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(onClick = {
        if (viewName.isBlank()) viewError = "请输入名称"
        else if (state.views.any { it.name == viewName.trim() }) viewError = "名称已存在"
        else { actions.saveView(SavedView(viewName.trim(), domain, week)); naming = false; viewName = "" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = { naming = false }) { Text("取消") } })
}

@Composable
private fun AssistantPage(state: AppState, actions: AppActions, showDraft: () -> Unit, settings: () -> Unit,showTasks:(String)->Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboard=LocalSoftwareKeyboardController.current
    fun send() { if(!state.assistantBusy && !state.saving && (text.isNotBlank() || state.pendingImage!=null)) { submitted=text.trim();actions.sendMessage(text.trim());keyboard?.hide() } }
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        val last=state.messages.lastOrNull {it.user}
        if(last?.user==true && last.text.removeSuffix("\n[图片]")==submitted) {
            if(text.trim()==submitted)text=""
            submitted=null
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.voiceText) {
        state.voiceText?.let { recognized -> text = if (text.isBlank()) recognized else "$text $recognized"; actions.consumeVoice() }
    }
    LaunchedEffect(state.messages.size, state.draft, state.assistantBusy,state.taskReceipt) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem((listState.layoutInfo.totalItemsCount-1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("助手", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(top = 8.dp, bottom = 14.dp))
            TextButton(onClick = settings) { Text(if (state.settings.enabled) state.settings.connections.getOrNull(state.settings.activeIndex)?.name ?: "模型" else "连接模型", fontSize = 12.sp) }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                if (state.messages.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 38.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(54.dp).background(Soft, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Glyph("mark", modifier = Modifier.size(28.dp)) }
                    Text("说一句，就安排好", fontWeight = FontWeight.Medium, fontSize = 20.sp, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                    Text("安排日程、记录训练，也记住你的习惯", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = { text = "明天晚上八点练胸40分钟，提前10分钟提醒" }, shape = RoundedCornerShape(18.dp)) { Text("安排明天的训练", fontSize = 13.sp) }
                    if (!state.settings.enabled) Text("本地可识别文字记录；规划需连接模型", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
                }
            }
            itemsIndexed(state.messages, key = { _,message -> message.id }) { index,message ->
                Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start) {
                    Text(message.text, Modifier.widthIn(max = 308.dp).then(if (message.user) Modifier.background(Soft, RoundedCornerShape(22.dp)).padding(16.dp) else Modifier.padding(vertical = 4.dp)),
                        color = Ink, fontSize = 15.sp, lineHeight = 24.sp)
                }
                state.taskReceipt?.takeIf {it.messageId==message.id && it.after.any {task ->state.plans.any {it.id==task.id}}}?.let {
                    TaskReceiptCard(it,state,actions,showTasks)
                }
                if(message.failed && index==state.messages.lastIndex) {
                    val previous=state.messages.take(index).lastOrNull {it.user}
                    if(previous!=null)TextButton(enabled=!state.assistantBusy && !state.saving,onClick={
                        text=previous.text.removeSuffix("\n[图片]");send()
                    },modifier=Modifier.testTag("assistant-retry")){Text("重试")}
                }
                }
            }
            if (state.assistantBusy) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(state.assistantStatus.ifBlank { "正在思考" }, fontSize = 13.sp, color = Muted)
                    TextButton(onClick = actions::cancelAssistant) { Text("停止", fontSize = 13.sp) }
                }
            }
            state.taskReceipt?.takeIf {receipt ->receipt.messageId==null && receipt.after.any {task ->state.plans.any {it.id==task.id}}}?.let {receipt -> item(key="task-receipt") {
                TaskReceiptCard(receipt,state,actions,showTasks)
            } }
            state.draft?.let { draft -> item {
                Surface(shape = RoundedCornerShape(20.dp), color = Soft) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text("待确认", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(listOfNotNull(if(draft.records.isNotEmpty())"${draft.records.size} 条记录" else null,
                            if(draft.plans.isNotEmpty())"${draft.plans.size} 个任务" else null,if(draft.memories.isNotEmpty())"${draft.memories.size} 条记忆" else null).joinToString(" · "),
                            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                        draft.memories.take(3).forEach { Text(it.text,fontSize=14.sp,modifier=Modifier.padding(vertical=4.dp)) }
                        draft.records.take(3).forEach { Text("${it.title}  ${recordValue(it)}", fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                        draft.plans.take(3).forEach { Text("${it.date.takeLast(5)}  ${it.title}", fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                        Button(onClick = {actions.confirmDraft(draft)},enabled=!state.saving && !state.assistantBusy,modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("chat-confirm-draft"), shape = RoundedCornerShape(16.dp)) {
                            Text(if(state.saving)"保存中…" else if(draft.records.isNotEmpty())"保存记录" else if(draft.memories.isNotEmpty())"记住" else "创建任务")
                        }
                        Row {TextButton(onClick=showDraft){Text("核对并保存")};TextButton(onClick=actions::dismissDraft,enabled=!state.assistantBusy && !state.saving){Text("放下")}}
                    }
                }
            } }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            state.pendingImage?.let {
                Row(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(12.dp)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("image", modifier = Modifier.size(18.dp)); Text("已选图片", Modifier.weight(1f).padding(start = 10.dp), fontSize = 13.sp)
                    IconButton(onClick = actions::removeImage) { Glyph("close", description = "移除图片") }
                }
                Text(if(state.settings.enabled && state.settings.connections.getOrNull(state.settings.activeIndex)?.supportsImages==true)
                    "照片将交给当前模型识别" else "照片作为本地附件，请补充文字记录", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
            Surface(color = Soft, shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TextField(text, { text = it }, Modifier.fillMaxWidth().testTag("assistant-input"), placeholder = { Text("说说刚做了什么…", color = Muted) },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Soft, unfocusedContainerColor = Soft, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        maxLines = 5,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = actions::selectImage, enabled = !state.assistantBusy) { Glyph("image", description = "添加图片") }
                        TextButton(onClick=actions::takePhoto,enabled=!state.assistantBusy) { Text("拍照",fontSize=12.sp) }
                        IconButton(onClick = actions::voiceInput, enabled = !state.assistantBusy) { Glyph("mic", description = "语音输入") }
                        Spacer(Modifier.weight(1f))
                        FilledIconButton(onClick = ::send,
                            enabled = !state.assistantBusy && !state.saving && (text.isNotBlank() || state.pendingImage != null), modifier = Modifier.size(48.dp).testTag("assistant-send")) { Glyph("up", Color.White, description = "发送") }
                    }
                }
            }
        }
    }
}

@Composable private fun TaskReceiptCard(receipt:TaskReceipt,state:AppState,actions:AppActions,showTasks:(String)->Unit) {
    Surface(shape=RoundedCornerShape(20.dp),color=Soft,modifier=Modifier.padding(top=8.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(if(receipt.createdIds.isEmpty())"已更新 ${receipt.after.size} 个任务" else if(receipt.before.isEmpty())"已创建 ${receipt.after.size} 个任务" else "已保存 ${receipt.after.size} 项安排",fontWeight=FontWeight.Medium)
            receipt.after.take(4).forEach {task ->Text("${task.date.takeLast(5)} ${task.time}  ${task.title}",fontSize=14.sp,modifier=Modifier.padding(top=8.dp))}
            if(receipt.after.size>4)Text("共 ${receipt.after.size} 个任务",fontSize=13.sp,color=Muted)
            if(receipt.after.any {it.reminderMinutes!=null} && !state.notificationsAllowed)
                TextButton(onClick=actions::requestNotifications){Text("开启通知，启用提醒")}
            Row {
                TextButton(onClick={showTasks(receipt.after.first().date)}){Text("查看日程")}
                TextButton(onClick=actions::undoAgentTasks,enabled=!state.assistantBusy && !state.saving){Text("撤销上次操作")}
            }
        }
    }
}

@Composable internal fun PageTitle(text: String) { Text(text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)) }
@Composable internal fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null) { if (onAction != null) TextButton(onClick = onAction) { Text(action, fontSize = 12.sp) } else Text(action, color = Muted, fontSize = 12.sp) }
    }
}
@Composable private fun EmptyLine(text: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(text, color = Muted, fontSize = 14.sp)
        TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(action, fontSize = 13.sp) }
    }
}
@Composable private fun Metric(label: String, value: String) { Column { Text(label, color = Muted, fontSize = 12.sp); Text(value, fontSize = 21.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 5.dp)) } }
@Composable internal fun PlanRow(plan: Plan, onClick: () -> Unit, onComplete: () -> Unit, showDate: Boolean = false) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(Soft, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Glyph(if (plan.status == PlanStatus.DONE) "check" else if (plan.status == PlanStatus.SKIPPED) "skip" else "clock", Muted, Modifier.size(19.dp)) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(plan.title, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = listOf(if (showDate) plan.date.takeLast(5) else "", plan.time.ifEmpty { "待安排" }, plan.domain.label,
                if (plan.domain != Domain.FINANCE) "${plan.expectedMinutes} 分钟" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
        if (plan.status in setOf(PlanStatus.TODO,PlanStatus.IN_PROGRESS)) TextButton(onClick = onComplete,modifier=Modifier.testTag("plan-complete-${plan.id}")) { Text("完成", fontSize = 13.sp) }
        else Text(plan.status.label, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(12.dp))
    }
}
@Composable internal fun RecordRow(record: LifeRecord, goals: List<WeeklyGoal>, showDate: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(Soft, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Glyph(when (record.domain) { Domain.TRAINING -> "training"; Domain.FINANCE -> "money"; else -> "book" }, Muted, Modifier.size(19.dp)) }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Text(record.title, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOf(if (showDate) record.date.takeLast(5) else "", record.domain.label,
                if (record.domain == Domain.FINANCE) record.category else goals.firstOrNull { it.id == record.goalId }?.title.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
            Text(detail, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(recordValue(record), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
internal fun recordValue(record: LifeRecord): String = if (record.domain == Domain.FINANCE) {
    (when(record.moneyKind) { MoneyKind.EXPENSE -> "−"; MoneyKind.INCOME, MoneyKind.REFUND -> "+"; MoneyKind.TRANSFER -> "↔" }) + "¥" + Amounts.yuan(record.amountFen)
} else listOfNotNull(if(record.count>0) "${record.count} ${record.countUnit}" else null,if(record.minutes>0) "${record.minutes} 分钟" else null)
    .joinToString(" · ").ifBlank {if(record.planId!=null && record.completesPlan)"已完成" else "已记录"}
@Composable internal fun GoalRow(goal: WeeklyGoal, records: List<LifeRecord>, date: LocalDate, onClick: () -> Unit) {
    val value = Progress.value(goal, records, date)
    val fill by animateFloatAsState((value.toFloat() / goal.target.coerceAtLeast(1)).coerceIn(0f, 1f), label = "目标进度")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(goal.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("$value / ${goal.target} ${if (goal.mode == GoalMode.COUNT) "次" else "分钟"}", color = Muted, fontSize = 12.sp)
        }
        LinearProgressIndicator(progress = { fill }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(4.dp).clip(CircleShape), color = Ink, trackColor = Line, drawStopIndicator = {})
    }
}

@Composable internal fun ChoiceRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected = index == selected, onClick = { onSelect(index) }, label = { Text(label, fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Ink, selectedLabelColor = Color.White),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = index == selected, borderColor = Line, selectedBorderColor = Ink))
        }
    }
}

@Composable internal fun Glyph(name: String, color: Color = Ink, modifier: Modifier = Modifier.size(22.dp), description: String? = null) {
    Canvas(modifier.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val s = size.minDimension
        val u = s / 24f
        val stroke = 1.65f * u
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), stroke, StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float = 2f) = drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(radius * u), style = Stroke(stroke))
        when (name) {
            "mark" -> { line(5f, 18f, 9f, 6f); line(9f, 6f, 13f, 15f); line(13f, 15f, 18f, 6f); line(18f, 6f, 19f, 18f) }
            "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "up" -> { line(12f, 19f, 12f, 5f); line(6f, 11f, 12f, 5f); line(12f, 5f, 18f, 11f) }
            "left" -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
            "right" -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
            "check" -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 19f, 7f) }
            "skip" -> { line(6f, 7f, 6f, 17f); line(10f, 7f, 17f, 12f); line(17f, 12f, 10f, 17f) }
            "calendar", "today" -> { rect(3f, 5f, 18f, 16f, 3f); line(3f, 10f, 21f, 10f); line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f); if (name == "today") { line(8f, 15f, 11f, 18f); line(11f, 18f, 17f, 13f) } else { drawCircle(color, 1.2f*u, Offset(8f*u, 15f*u)); drawCircle(color, 1.2f*u, Offset(15f*u, 15f*u)) } }
            "chat" -> { val path = Path().apply { moveTo(5f*u, 4f*u); lineTo(19f*u, 4f*u); quadraticBezierTo(21f*u,4f*u,21f*u,7f*u); lineTo(21f*u,16f*u); quadraticBezierTo(21f*u,19f*u,18f*u,19f*u); lineTo(9f*u,19f*u); lineTo(4f*u,22f*u); lineTo(4f*u,7f*u); close() }; drawPath(path,color,style=Stroke(stroke)); line(8f,10f,17f,10f); line(8f,14f,14f,14f) }
            "grid" -> { rect(3f,3f,7f,7f); rect(14f,3f,7f,7f); rect(3f,14f,7f,7f); rect(14f,14f,7f,7f) }
            "settings" -> { drawCircle(color, 8f*u, center, style = Stroke(stroke)); drawCircle(color, 3f*u, center, style = Stroke(stroke)); line(12f,1f,12f,4f); line(12f,20f,12f,23f); line(1f,12f,4f,12f); line(20f,12f,23f,12f) }
            "clock" -> { drawCircle(color, 9f*u, center, style = Stroke(stroke)); line(12f,6f,12f,12f); line(12f,12f,16f,14f) }
            "search" -> { drawCircle(color, 6.5f*u, Offset(10f*u,10f*u), style = Stroke(stroke)); line(15f,15f,21f,21f) }
            "table" -> { rect(3f,4f,18f,16f); line(3f,10f,21f,10f); line(10f,4f,10f,20f); line(3f,15f,21f,15f) }
            "list" -> { line(8f,6f,21f,6f); line(8f,12f,21f,12f); line(8f,18f,21f,18f); for(y in listOf(6f,12f,18f)) drawCircle(color,u,Offset(3f*u,y*u)) }
            "book" -> { rect(4f,3f,16f,18f); line(8f,3f,8f,21f); line(11f,8f,16f,8f) }
            "money" -> { rect(2f,5f,20f,14f,3f); drawCircle(color,3.2f*u,center,style=Stroke(stroke)); line(5f,9f,5f,15f); line(19f,9f,19f,15f) }
            "training" -> { line(3f,8f,3f,16f); line(6f,5f,6f,19f); line(6f,12f,18f,12f); line(18f,5f,18f,19f); line(21f,8f,21f,16f) }
            "image" -> { rect(3f,3f,18f,18f,3f); drawCircle(color,1.8f*u,Offset(8f*u,8f*u)); line(4f,18f,10f,12f); line(10f,12f,14f,16f); line(14f,16f,17f,12f); line(17f,12f,21f,16f) }
            "mic" -> { rect(8f,2f,8f,14f,4f); drawArc(color,0f,180f,false,Offset(5f*u,7f*u),Size(14f*u,12f*u),style=Stroke(stroke)); line(12f,19f,12f,23f); line(8f,23f,16f,23f) }
        }
    }
}
