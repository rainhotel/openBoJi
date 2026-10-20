package com.boji.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import com.boji.app.data.ModelJson
import org.json.JSONObject
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boji.app.model.*

private val TaskColumns=linkedMapOf("date" to "安排时间","deadline" to "截止时间","priority" to "优先级","domain" to "类型","status" to "状态",
    "project" to "项目","expected" to "预计","actual" to "实际","photos" to "照片","parent" to "父任务")
private val SortColumns=TaskColumns.filterKeys { it in setOf("date","deadline","priority","domain","status") }

@Composable internal fun TaskWorkspace(state: AppState,actions: AppActions,record:(LifeRecord)->Unit,edit:(Plan)->Unit,complete:(Plan)->Unit) {
    var table by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable(stateSaver=Saver<SavedView,String>(save={ModelJson.view(it).toString()},restore={ModelJson.view(JSONObject(it))})) { mutableStateOf(SavedView("当前任务",scope="tasks")) }
    var dialog by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val rows=TaskQuery.select(state.plans,view,query)
    val compact=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal=24.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("日程",fontSize=if(compact)18.sp else 28.sp,modifier=Modifier.weight(1f).padding(vertical=if(compact)4.dp else 12.dp))
                if(compact) {
                    FilterChip(selected=!table,onClick={table=false},label={Text("日历")})
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected=table,onClick={table=true},label={Text("多维表格")})
                }
                TextButton(onClick={edit(Plan())}){Text("加计划")}
            }
            if(!compact)ChoiceRow(listOf("日历","多维表格"),if(table)1 else 0){table=it==1}
            if(!compact && rows.any { it.reminderMinutes!=null } && !state.notificationsAllowed)
                TextButton(onClick=actions::requestNotifications){Text("提醒未启用，开启系统通知",fontSize=12.sp)}
            if(!compact)OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(top=8.dp),singleLine=true,label={Text("搜索任务或项目")},shape=RoundedCornerShape(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                TextButton(onClick={dialog="filter"}){Text("筛选")};TextButton(onClick={dialog="sort"}){Text("排序 ${view.sorts.size}")}
                TextButton(onClick={dialog="group"}){Text("分组")};if(table)TextButton(onClick={dialog="columns"}){Text("显示列")}
                TextButton(onClick={name="";dialog="save"}){Text(if(compact)"视图" else "保存视图")}
                if(compact && rows.any { it.reminderMinutes!=null } && !state.notificationsAllowed)
                    TextButton(onClick=actions::requestNotifications){Text("开启提醒")}
            }
            if(!compact && state.views.any { it.scope=="tasks" })Row(Modifier.horizontalScroll(rememberScrollState())) {
                state.views.filter { it.scope=="tasks" }.forEach { saved ->
                    InputChip(selected=view==saved,onClick={view=saved},label={Text(saved.name)},modifier=Modifier.padding(end=6.dp))
                }
            }
        }
        if(!table)CalendarPage(state.copy(plans=rows,records=state.records.filter { view.domain==null || it.domain==view.domain }),record,edit,complete,showTitle=false)
        else {
            Text("${rows.size} 个任务 · ${view.sorts.joinToString(" → ") { SortColumns[it.removePrefix("-")].orEmpty() + if(it.startsWith("-"))"↓" else "↑" }}",
                color=Muted,fontSize=12.sp,modifier=Modifier.padding(horizontal=24.dp,vertical=8.dp))
            val horizontal=rememberScrollState()
            Box(Modifier.weight(1f).horizontalScroll(horizontal).padding(horizontal=24.dp)) {
                val width=180+view.columns.size*120
                LazyColumn(Modifier.width(width.dp).testTag("task-table"),contentPadding=PaddingValues(bottom=110.dp)) {
                    item { Row(Modifier.background(Soft)) { TaskCell("事项",180);view.columns.forEach { TaskCell(TaskColumns[it].orEmpty(),120) } } }
                    val groups=if(view.groupBy.isBlank())linkedMapOf("" to rows) else rows.groupBy { TaskQuery.group(it,view.groupBy) }
                    groups.forEach { (group,plans) ->
                        if(group.isNotEmpty())item(key="group:$group") { Text("$group · ${plans.size}",modifier=Modifier.padding(vertical=14.dp),fontSize=14.sp) }
                        items(plans,key={it.id}) { plan ->
                            val actual=state.records.filter { it.planId==plan.id }
                            Row(Modifier.fillMaxWidth().clickable { edit(plan) }.testTag("task-${plan.id}").heightIn(min=62.dp)) {
                                TaskCell(plan.title,180)
                                view.columns.forEach { key ->
                                    val text=when(key) {
                                        "date" -> plan.date.takeLast(5)+" "+plan.time
                                        "deadline" -> plan.deadline.ifBlank { "未设置" }.replace("T"," ")
                                        "priority" -> "P${plan.priority}";"domain" -> plan.domain.label;"status" -> plan.status.label
                                        "project" -> plan.project.ifBlank { "—" };"expected" -> if(plan.expectedMinutes==0)"未设置" else "${plan.expectedMinutes} 分钟"
                                        "actual" -> actual.map(::recordValue).joinToString(" / ").ifBlank { "—" }
                                        "photos" -> "${actual.sumOf { it.attachments.size.coerceAtLeast(if(it.sourceId?.startsWith("image:")==true)1 else 0) }} 张"
                                        "parent" -> state.plans.find { it.id==plan.parentId }?.title ?: "—"
                                        else -> ""
                                    }
                                    TaskCell(text,120)
                                }
                            }
                            HorizontalDivider(color=Line)
                        }
                    }
                    if(rows.isEmpty())item { Text("没有匹配的任务",color=Muted,modifier=Modifier.padding(vertical=24.dp)) }
                }
            }
        }
    }
    if(dialog.isNotEmpty())AlertDialog(onDismissRequest={dialog=""},title={Text(when(dialog){"sort"->"多条件排序";"filter"->"筛选任务";"group"->"按字段分组";"columns"->"显示列";else->"保存任务视图"})},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            when(dialog) {
                "sort" -> {
                    view.sorts.forEachIndexed { index,key -> Row {
                        Text("${index+1}. ${SortColumns[key.removePrefix("-")]}",Modifier.weight(1f).padding(top=12.dp))
                        TextButton(onClick={view=view.copy(sorts=view.sorts.map { if(it==key)if(it.startsWith("-"))it.drop(1) else "-$it" else it })}){Text(if(key.startsWith("-"))"降序" else "升序")}
                        if(index>0)TextButton(onClick={ val list=view.sorts.toMutableList();list[index]=list[index-1];list[index-1]=key;view=view.copy(sorts=list) }){Text("上移")}
                        if(view.sorts.size>1)TextButton(onClick={view=view.copy(sorts=view.sorts-key)}){Text("移除")}
                    } }
                    SortColumns.filterKeys { candidate -> view.sorts.none { it.removePrefix("-")==candidate } }.forEach { (key,label) ->
                        TextButton(onClick={view=view.copy(sorts=view.sorts+key)}){Text("添加：$label")}
                    }
                    Text("未设置截止时间的任务排在最后。",fontSize=12.sp,color=Muted)
                }
                "filter" -> {
                    if(compact)OutlinedTextField(query,{query=it},singleLine=true,label={Text("搜索任务或项目")})
                    Text("类型");ChoiceRow(listOf("全部")+Domain.entries.map { it.label },view.domain?.ordinal?.plus(1) ?: 0){view=view.copy(domain=if(it==0)null else Domain.entries[it-1])}
                    Text("状态");ChoiceRow(listOf("全部（不含归档）")+PlanStatus.entries.map { it.label },PlanStatus.entries.indexOfFirst { it.name==view.status }+1){view=view.copy(status=if(it==0)"" else PlanStatus.entries[it-1].name)}
                    ToggleRow("仅本周",view.thisWeek){view=view.copy(thisWeek=it)}
                }
                "group" -> {
                    val keys=listOf("","domain","status","priority","project")
                    ChoiceRow(listOf("不分组","类型","状态","优先级","项目"),keys.indexOf(view.groupBy)){view=view.copy(groupBy=keys[it])}
                }
                "columns" -> TaskColumns.forEach { (key,label) -> Row {
                    Checkbox(checked=key in view.columns,onCheckedChange={ checked -> view=view.copy(columns=if(checked)view.columns+key else view.columns-key) });Text(label,Modifier.padding(top=14.dp))
                } }
                "save" -> {
                    if(compact)state.views.filter {it.scope=="tasks"}.forEach { saved ->
                        TextButton(onClick={view=saved;dialog=""}) {Text("使用：${saved.name}")}
                    }
                    OutlinedTextField(name,{name=it.take(40)},singleLine=true,label={Text("视图名称")})
                }
            }
        }},confirmButton={TextButton(onClick={if(dialog=="save" && name.isNotBlank())actions.saveView(view.copy(name=name.trim()));dialog=""},enabled=dialog!="save" || (name.isNotBlank() && state.views.none { it.scope!="tasks" && it.name==name.trim() })){Text(if(dialog=="save")"保存" else "完成")}})
}

@Composable private fun TaskCell(text: String,width: Int) { Text(text,Modifier.width(width.dp).padding(10.dp),fontSize=13.sp,maxLines=3) }
