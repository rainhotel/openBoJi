package com.boji.app.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boji.app.model.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BojiStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var store: BojiStore
    private val extras = mutableListOf<Pair<String, BojiStore>>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "store-test-${UUID.randomUUID()}.db"
        store = BojiStore(context, databaseName)
    }

    @After
    fun tearDown() {
        store.close(); context.deleteDatabase(databaseName)
        extras.forEach { (name, instance) -> instance.close(); context.deleteDatabase(name) }
    }

    @Test
    fun freshDatabaseIsEmpty() {
        val state = store.snapshot()
        assertFalse(state.loading)
        assertTrue(state.records.isEmpty())
        assertTrue(state.plans.isEmpty())
        assertTrue(state.goals.isEmpty())
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun reloadPersistsFactsSettingsViewsChatAndPendingDraft() {
        val goal = WeeklyGoal(title = "每周运动")
        val record = LifeRecord(title = "练拳", minutes = 40, goalId = goal.id)
        val plan = Plan(title = "学 Kotlin", date = "2026-10-20")
        val settings = ModelSettings(connections = listOf(ModelConnection(apiKey = "secret-test-key")))
        val message = ChatMessage(user = true, text = "今天练了四十分钟")
        val pending = DraftBatch(records = listOf(LifeRecord(title = "阅读", domain = Domain.LEARNING, minutes = 20)))
        store.saveGoal(goal); store.saveRecord(record); store.savePlan(plan)
        store.saveSettings(settings); store.saveView(SavedView("本周训练", Domain.TRAINING, true))
        store.saveMessage(message); store.savePendingDraft(pending)
        store.close()
        store = BojiStore(context, databaseName)
        val restored = store.snapshot()
        assertEquals(listOf(record), restored.records)
        assertEquals(listOf(plan), restored.plans)
        assertEquals(listOf(goal), restored.goals)
        assertEquals(settings, restored.settings)
        assertEquals(listOf(message), restored.messages)
        assertEquals(pending, restored.draft)
        assertEquals(listOf(SavedView("本周训练", Domain.TRAINING, true)), restored.views)

        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.rawQuery("SELECT payload FROM meta WHERE name = 'model-settings'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val raw = cursor.getString(0)
                assertFalse(raw.contains("secret-test-key"))
                assertTrue(raw.contains("apiKeyCipher"))
            }
        }
    }

    @Test
    fun confirmingDraftIsAtomicAndIdempotentAcrossReload() {
        val plan = Plan(title = "晚间训练", domain = Domain.TRAINING)
        val record = LifeRecord(title = "练拳", minutes = 30, planId = plan.id)
        val draft = DraftBatch(records = listOf(record), plans = listOf(plan))
        store.savePendingDraft(draft)
        store.applyDraft(draft)
        assertNull(store.snapshot().draft)
        assertEquals(PlanStatus.DONE, store.snapshot().plans.single().status)
        store.close(); store = BojiStore(context, databaseName)
        store.applyDraft(draft)
        assertEquals(1, store.snapshot().records.size)
        assertEquals(1, store.snapshot().plans.size)
    }

    @Test
    fun invalidBatchRollsBackPlanAndFacts() {
        val plan = Plan(title = "不能留下半份计划", domain = Domain.TRAINING)
        val invalidRecord = LifeRecord(title = "无效日期", date = "2026-02-30", planId = plan.id)
        fails { store.applyDraft(DraftBatch(records = listOf(invalidRecord), plans = listOf(plan))) }
        assertTrue(store.snapshot().records.isEmpty())
        assertTrue(store.snapshot().plans.isEmpty())
    }

    @Test
    fun oneFactPerPlanAndDeletingFactResetsPlan() {
        val plan = Plan(title = "训练", domain = Domain.TRAINING)
        store.savePlan(plan)
        val record = LifeRecord(title = "完成训练", minutes = 30, planId = plan.id)
        store.saveRecord(record)
        assertEquals(PlanStatus.DONE, store.snapshot().plans.single().status)
        fails { store.saveRecord(record.copy(id = "second-fact")) }
        fails { store.skipPlan(plan.id) }
        assertEquals(1, store.snapshot().records.size)
        store.deleteRecord(record.id)
        assertTrue(store.snapshot().records.isEmpty())
        assertEquals(PlanStatus.TODO, store.snapshot().plans.single().status)
        store.skipPlan(plan.id)
        assertEquals(PlanStatus.SKIPPED, store.snapshot().plans.single().status)
    }

    @Test
    fun deletingPlanPreservesActualRecord() {
        val plan = Plan(title = "训练", domain = Domain.TRAINING)
        val record = LifeRecord(title = "实际训练", minutes = 30, planId = plan.id)
        store.savePlan(plan); store.saveRecord(record); store.deletePlan(plan.id)
        assertTrue(store.snapshot().plans.isEmpty())
        assertEquals(record.copy(planId = null), store.snapshot().records.single())
    }

    @Test
    fun backupRoundTripMergesAndExcludesCredentialsChatAndDraft() {
        val goal = WeeklyGoal(title = "读书", domain = Domain.LEARNING, mode = GoalMode.MINUTES, target = 90)
        val record = LifeRecord(title = "读书半小时", domain = Domain.LEARNING, goalId = goal.id, minutes = 30)
        val batch = DraftBatch(records = listOf(record))
        store.saveGoal(goal); store.applyDraft(batch)
        store.saveView(SavedView("学习", Domain.LEARNING))
        store.saveSettings(ModelSettings(
            monthlyBudgetFen = 150000,
            memory = "周四晚上训练",
            connections = listOf(ModelConnection(name = "private-provider", endpoint = "https://private.example/v1", apiKey = "backup-must-not-contain-this")),
        ))
        store.saveMessage(ChatMessage(user = true, text = "private-chat-content"))
        store.savePendingDraft(DraftBatch(records = listOf(LifeRecord(title = "unconfirmed-secret-draft"))))
        val json = store.exportJson()
        listOf("backup-must-not-contain-this", "private-provider", "private.example", "private-chat-content", "unconfirmed-secret-draft", "apiKey").forEach {
            assertFalse(json.contains(it))
        }
        val restored = extraStore()
        val existing = LifeRecord(title = "本机已有记录", domain = Domain.FINANCE, amountFen = 2800)
        restored.saveRecord(existing)
        restored.saveSettings(ModelSettings(connections = listOf(ModelConnection(apiKey = "keep-existing-key"))))
        restored.importJson(json)
        restored.applyDraft(batch)
        val state = restored.snapshot()
        assertEquals(2, state.records.size)
        assertTrue(state.records.containsAll(listOf(existing, record)))
        assertEquals(listOf(goal), state.goals)
        assertEquals(150000L, state.settings.monthlyBudgetFen)
        assertEquals("周四晚上训练", state.settings.memory)
        assertEquals("keep-existing-key", state.settings.connections.single().apiKey)
        assertTrue(state.messages.isEmpty())
        assertNull(state.draft)
    }

    @Test
    fun malformedBackupRollsBackAllRowsAndSettings() {
        val existing = LifeRecord(title = "原有记录", minutes = 20)
        store.saveRecord(existing)
        store.saveSettings(ModelSettings(monthlyBudgetFen = 88800))
        val source = extraStore()
        source.saveRecord(LifeRecord(title = "新记录", domain = Domain.FINANCE, amountFen = 500))
        val corrupted = JSONObject(source.exportJson())
        corrupted.getJSONArray("records").getJSONObject(0).put("amountFen", "5.00")
        fails { store.importJson(corrupted.toString()) }
        assertEquals(listOf(existing), store.snapshot().records)
        assertEquals(88800L, store.snapshot().settings.monthlyBudgetFen)

        val missingGoal = JSONObject(source.exportJson())
        missingGoal.getJSONArray("records").getJSONObject(0).put("goalId", "missing-goal")
        fails { store.importJson(missingGoal.toString()) }
        assertEquals(listOf(existing), store.snapshot().records)
    }

    @Test
    fun invalidDomainValuesNeverReachDatabase() {
        fails { store.saveRecord(LifeRecord(title = "负时长", minutes = -1)) }
        fails { store.saveRecord(LifeRecord(title = "零元账目", domain = Domain.FINANCE)) }
        fails { store.saveRecord(LifeRecord(title = "不存在目标", goalId = "missing")) }
        fails { store.savePlan(Plan(title = "没有事实的完成", status = PlanStatus.DONE)) }
        fails { store.saveGoal(WeeklyGoal(title = "错误财务目标", domain = Domain.FINANCE)) }
        assertTrue(store.snapshot().records.isEmpty())
        assertTrue(store.snapshot().plans.isEmpty())
        assertTrue(store.snapshot().goals.isEmpty())
    }

    @Test
    fun repeatRulesAndExceptionsRestoreAtomically() {
        val plan=Plan(id="repeat:2026-10-21",title="练拳",domain=Domain.TRAINING,date="2026-10-21",seriesId="repeat")
        val rule=JSONObject().put("id","repeat").put("title","练拳").put("domain","TRAINING")
            .put("date","2026-10-21").put("time","").put("minutes",40).put("days",org.json.JSONArray(listOf(3)))
        val recurrence=JSONObject().put("rules",org.json.JSONArray().put(rule)).put("exceptions",org.json.JSONArray())
        store.savePlans(listOf(plan),recurrence.toString())
        store.deletePlan(plan.id)
        assertTrue(store.recurrenceJson().contains(plan.id))
        val restored=extraStore()
        restored.importJson(store.exportJson())
        assertEquals(JSONObject(store.recurrenceJson()).toString(),JSONObject(restored.recurrenceJson()).toString())
        val bad=JSONObject(store.exportJson())
        bad.getJSONObject("recurrence").getJSONArray("rules").getJSONObject(0).put("minutes",-1)
        fails { restored.importJson(bad.toString()) }
        assertEquals(store.recurrenceJson(),restored.recurrenceJson())
        assertTrue(restored.snapshot().plans.isEmpty())
    }

    private fun extraStore(): BojiStore {
        val name = "store-test-extra-${UUID.randomUUID()}.db"
        return BojiStore(context, name).also { extras += name to it }
    }

    private fun fails(block: () -> Unit) { assertTrue("Expected rejected mutation", runCatching(block).isFailure) }
}
