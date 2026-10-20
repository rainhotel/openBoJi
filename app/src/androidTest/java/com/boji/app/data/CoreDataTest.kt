package com.boji.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boji.app.model.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CoreDataTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="core-${UUID.randomUUID()}.db"
    private lateinit var store: BojiStore
    @Before fun setup(){store=BojiStore(context,name)}
    @After fun cleanup(){store.close();context.deleteDatabase(name)}

    @Test fun oldPayloadReadsWithoutLosingExistingFacts() {
        val record=LifeRecord(title="旧记录",minutes=20)
        val raw=ModelJson.record(record).apply { listOf("count","countUnit","attachments","completesPlan").forEach(::remove) }
        assertEquals(record,ModelJson.record(raw))
        val plan=Plan(title="旧任务")
        val old=ModelJson.plan(plan).apply { listOf("deadline","priority","project","parentId","reminderMinutes").forEach(::remove) }
        assertEquals(plan,ModelJson.plan(old))
    }
    @Test fun multipleActivitiesHaveExplicitPartialAndCompletionStates() {
        val plan=Plan(title="练胸",domain=Domain.TRAINING);store.savePlan(plan)
        val first=LifeRecord(title="卧推",count=3,countUnit="组",planId=plan.id,completesPlan=false)
        store.saveRecord(first);assertEquals(PlanStatus.IN_PROGRESS,store.snapshot().plans.single().status)
        val last=LifeRecord(title="练胸结束",count=7,planId=plan.id,attachments=listOf("test-photo.jpg"))
        store.saveRecord(last);assertEquals(PlanStatus.DONE,store.snapshot().plans.single().status)
        store.deleteRecord(last.id);assertEquals(PlanStatus.IN_PROGRESS,store.snapshot().plans.single().status)
        store.savePlan(store.snapshot().plans.single().copy(status=PlanStatus.ARCHIVED))
        assertEquals(first,store.snapshot().records.single())
        store.close();store=BojiStore(context,name);assertEquals(PlanStatus.ARCHIVED,store.snapshot().plans.single().status)
    }
    @Test fun examAndRevisionBatchPreservesOldScheduleAndRejectsCyclesAtomically() {
        val old=Plan(title="旧安排",date="2026-11-01",time="19:00");store.savePlan(old)
        val exam=Plan(title="英语考试",date="2026-11-03",deadline="2026-11-03T09:00",priority=0)
        val revision=Plan(title="复习",date="2026-11-01",time="19:30",parentId=exam.id,reminderMinutes=15)
        val batch=DraftBatch(plans=listOf(exam,revision));store.applyDraft(batch);store.applyDraft(batch)
        assertEquals(3,store.snapshot().plans.size);assertEquals(old,store.snapshot().plans.find { it.id==old.id })
        val a=Plan(id="cycle-a",title="a",parentId="cycle-b");val b=Plan(id="cycle-b",title="b",parentId="cycle-a")
        assertTrue(runCatching {store.applyDraft(DraftBatch(plans=listOf(a,b)))}.isFailure)
        assertEquals(3,store.snapshot().plans.size)
    }
    @Test fun memoryAndTaskViewBackupSurviveReloadAndDeletionDoesNotResurrectLegacy() {
        store.saveSettings(ModelSettings(memory="旧偏好".repeat(500)))
        assertEquals("旧偏好".repeat(500),store.snapshot().memories.joinToString(""){it.text})
        val memory=MemoryEntry(text="周二不能学习");store.applyDraft(DraftBatch(memories=listOf(memory)))
        val view=SavedView("考试优先",sorts=listOf("deadline","priority"),groupBy="domain",columns=listOf("date","deadline","status"),scope="tasks")
        store.saveView(view)
        val exported=store.exportJson();assertTrue(exported.contains(memory.text))
        val second=BojiStore(context,"$name-restored")
        try {
            second.importJson(exported);assertEquals(view,second.snapshot().views.single())
            assertTrue(second.snapshot().memories.any {it.id==memory.id})
        } finally {second.close();context.deleteDatabase("$name-restored")}
        store.snapshot().memories.forEach {store.deleteMemory(it.id)}
        store.close();store=BojiStore(context,name);assertTrue(store.snapshot().memories.isEmpty())
    }
    @Test fun partialModelDraftIsEncryptedAndRestorable() {
        val value=ModelSettings(connections=listOf(ModelConnection(apiKey="TEST_ONLY_PRIVATE_DRAFT")))
        store.saveSettingsDraft(value);store.close();store=BojiStore(context,name)
        assertEquals(value,store.settingsDraft())
        val raw=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try { raw.rawQuery("SELECT payload FROM meta WHERE name='settings-draft'",null).use {it.moveToFirst();assertFalse(it.getString(0).contains("TEST_ONLY_PRIVATE_DRAFT"))} } finally {raw.close()}
    }
}
