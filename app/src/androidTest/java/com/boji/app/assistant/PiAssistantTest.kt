package com.boji.app.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boji.app.data.BojiStore
import com.boji.app.model.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Actual Android WebView + bundled upstream Pi loop + native ports. Provider responses are scripted, no paid API. */
@RunWith(AndroidJUnit4::class)
class PiAssistantTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private lateinit var store: BojiStore
    private lateinit var workspace: AgentWorkspace
    private var agent: PiAssistant? = null
    private val connection = ModelConnection(endpoint="https://example.com/v1",model="fixture",apiKey="DO_NOT_LEAK_KEY")
    private val settings = ModelSettings(enabled=true,connections=listOf(connection),includeHistory=true,memory="周四晚上训练")

    @Before fun setup() {
        store=BojiStore(context,"pi-test-$id.db")
        workspace=AgentWorkspace(context,"pi-test-$id")
    }
    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { agent?.close() }
        store.close(); context.deleteDatabase("pi-test-$id.db")
        java.io.File(context.filesDir,"pi-test-$id").deleteRecursively()
        Unit
    }
    private suspend fun create(transport: AssistantTransport): PiAssistant = withContext(Dispatchers.Main) {
        PiAssistant(context,store,transport,workspace=workspace).also { agent=it }
    }
    private fun call(name: String, args: JSONObject) = JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","tool_calls")
        .put("message",JSONObject().put("tool_calls",JSONArray().put(JSONObject().put("id",UUID.randomUUID().toString()).put("type","function")
            .put("function",JSONObject().put("name",name).put("arguments",args.toString()))))))).toString()
    private fun answer(text: String="已整理，请核对草稿") = JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop")
        .put("message",JSONObject().put("content",text)))).toString()
    private fun toolTexts(body: String) = JSONObject(body).getJSONArray("messages").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("role") == "tool" }.joinToString { it.optString("content") }
    }

    @Test fun skillToolsMemoryDraftAndRestartRunInsideAndroid() = runBlocking {
        store.saveSettings(settings)
        assertEquals(settings.memory,store.snapshot().settings.memory)
        store.saveRecord(LifeRecord(domain=Domain.TRAINING,title="原始练拳",minutes=40))
        var turn=0; val bodies=mutableListOf<String>()
        val runtime=create(AssistantTransport { _,key,body ->
            assertEquals(connection.apiKey,key); assertFalse(body.contains(connection.apiKey)); bodies+=body
            when(++turn) {
                1 -> call("read",JSONObject().put("path","/skills/weekly-review/SKILL.md"))
                2 -> call("search_records",JSONObject().put("domain","TRAINING"))
                3 -> call("memory_read",JSONObject())
                4 -> call("propose_draft",JSONObject().put("records",JSONArray()).put("plans",JSONArray().put(JSONObject()
                    .put("domain","TRAINING").put("title","练拳").put("date",LocalDate.now().plusDays(1).toString()).put("time","").put("expectedMinutes",30))))
                else -> answer()
            }
        })
        val result=withTimeout(30_000) { runtime.respond("请按本周记录安排明天练拳",store.snapshot()) }
        assertTrue(result.text,result.success); assertNotNull(result.draft)
        assertEquals(5,turn); assertEquals(1,store.snapshot().records.size); assertEquals(0,store.snapshot().plans.size)
        assertTrue(toolTexts(bodies[2]).contains("原始练拳")); assertTrue(toolTexts(bodies[3]).contains("周四晚上训练"))
        store.savePendingDraft(result.draft); store.applyDraft(result.draft!!); store.applyDraft(result.draft!!)
        assertEquals(1,store.snapshot().plans.size)
        withContext(Dispatchers.Main) { runtime.close() }
        var restored=""
        val restarted=create(AssistantTransport { _,_,body -> restored=body; answer("继续") })
        assertTrue(restarted.respond("继续",store.snapshot()).success)
        assertTrue(restored.contains("请按本周记录安排明天练拳"))
        assertTrue(restored.contains("tool_call_id"))
    }

    @Test fun ungrantedHistoryAndFinanceNeverEnterModelContext() = runBlocking {
        val finance=LifeRecord(domain=Domain.FINANCE,title="PRIVATE_FINANCE",amountFen=5000)
        val learning=LifeRecord(domain=Domain.LEARNING,title="PRIVATE_LEARNING",minutes=30)
        val state=AppState(settings=settings.copy(includeHistory=false,memory="PRIVATE_MEMORY"),records=listOf(finance,learning))
        var turn=0; val payloads=mutableListOf<String>()
        val runtime=create(AssistantTransport { _,_,body ->
            payloads+=body
            when(++turn) { 1 -> call("search_records",JSONObject()); 2 -> call("memory_read",JSONObject()); else -> answer("请开启授权") }
        })
        assertTrue(runtime.respond("帮我复盘",state).success)
        for(payload in payloads) {
            assertFalse(payload.contains("PRIVATE_FINANCE")); assertFalse(payload.contains("PRIVATE_LEARNING")); assertFalse(payload.contains("PRIVATE_MEMORY"))
        }
        assertTrue(toolTexts(payloads.last()).contains("尚未授权"))
        turn=0; payloads.clear()
        val permitted=state.copy(settings=settings.copy(includeHistory=true,includeFinance=false))
        assertTrue(runtime.respond("查看记录",permitted).success)
        assertTrue(payloads.last().contains("PRIVATE_LEARNING"))
        assertFalse(payloads.last().contains("PRIVATE_FINANCE"))
    }

    @Test fun permissionChangeDropsOldTranscriptAndSkillMetadataIsValidatedByPi() = runBlocking {
        val payloads=mutableListOf<String>()
        val runtime=create(AssistantTransport { _,_,body -> payloads+=body; answer("完成") })
        assertTrue(runtime.respond("SENSITIVE_OLD_TURN",AppState(settings=settings.copy(includeFinance=true))).success)
        assertTrue(runtime.respond("新问题",AppState(settings=settings.copy(includeFinance=false))).success)
        assertFalse(payloads.last().contains("SENSITIVE_OLD_TURN"))
        assertEquals("my-skill",runtime.validateSkill("---\nname: my-skill\ndescription: 我的技能\n---\n简短回答"))
        try { runtime.validateSkill("没有 frontmatter"); fail("invalid Skill accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun cancellationDoesNotSaveLateDraftAndNextRunStillWorks() = runBlocking {
        val started=CompletableDeferred<Unit>(); var turn=0
        val runtime=create(AssistantTransport { _,_,_ ->
            if (++turn == 1) { started.complete(Unit); delay(60_000) }
            answer("继续正常")
        })
        val job=async { runtime.respond("等待取消",AppState(settings=settings)) }
        withTimeout(15_000) { started.await() }; job.cancelAndJoin()
        assertNull(store.snapshot().draft)
        val reply=withTimeout(15_000) { runtime.respond("新请求",AppState(settings=settings)) }
        assertTrue(reply.text,reply.success); assertEquals("继续正常",reply.text)
    }
}
