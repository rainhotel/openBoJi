package com.boji.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import com.boji.app.data.ModelJson
import org.json.JSONObject
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boji.app.model.*

@Composable internal fun MemoryScreen(state: AppState,actions: AppActions,onClose: ()->Unit) {
    val memorySaver=Saver<MemoryEntry?,String>(save={it?.let { m->ModelJson.memory(m).toString() } ?: ""},restore={it.takeIf(String::isNotEmpty)?.let { s->ModelJson.memory(JSONObject(s)) }})
    var editing by rememberSaveable(stateSaver=memorySaver) { mutableStateOf<MemoryEntry?>(null) }
    var deleting by rememberSaveable(stateSaver=memorySaver) { mutableStateOf<MemoryEntry?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    BackHandler { onClose() }
    Scaffold(containerColor=androidx.compose.ui.graphics.Color.White,topBar={
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp)) {
            TextButton(onClick=onClose){Text("返回")};Text("长期记忆",fontSize=22.sp,modifier=Modifier.padding(14.dp))
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                FormError(state.error)
                Text("记住偏好、目标和时间限制",fontSize=17.sp)
                Text("清空聊天或切换模型不会删除；修改或遗忘后，旧聊天上下文会清空。",fontSize=13.sp,color=Muted,modifier=Modifier.padding(top=10.dp))
                ToggleRow("允许助手读取",state.settings.includeMemory) { actions.saveSettings(state.settings.copy(includeMemory=it)) }
                Button(onClick={editing=MemoryEntry(text="");text=""},modifier=Modifier.fillMaxWidth()){Text("添加记忆")}
            }
            if(state.memories.isEmpty())item {Text("还没有长期记忆。也可以对助手说“记住：周二晚上不能安排学习”。",color=Muted)}
            items(state.memories,key={it.id}) { memory ->
                Column {
                    Text(memory.text,fontSize=16.sp)
                    Text("${memory.source} · ${memory.updatedAt.take(10)}",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
                    Row {
                        TextButton(onClick={editing=memory;text=memory.text}){Text("修改")}
                        TextButton(onClick={deleting=memory}){Text("遗忘")}
                    }
                    HorizontalDivider(color=Line)
                }
            }
        }
    }
    editing?.let { memory -> AlertDialog(onDismissRequest={editing=null},title={Text("编辑记忆")},
        text={OutlinedTextField(text,{if(it.length<=1000)text=it},label={Text("记住什么")},minLines=3,maxLines=8)},
        confirmButton={TextButton(onClick={actions.saveMemory(memory.copy(text=text.trim(),updatedAt=java.time.LocalDateTime.now().toString()));editing=null},enabled=text.isNotBlank()){Text("保存")}},
        dismissButton={TextButton(onClick={editing=null}){Text("取消")}}) }
    deleting?.let { memory -> AlertDialog(onDismissRequest={deleting=null},title={Text("遗忘这条记忆？")},text={Text(memory.text)},
        confirmButton={TextButton(onClick={actions.deleteMemory(memory.id);deleting=null}){Text("遗忘")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}}) }
}
