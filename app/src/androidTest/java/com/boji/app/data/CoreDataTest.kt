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

    @Test fun agentTasksAreIdempotentAndUndoSurvivesRestartWithoutTouchingOtherData() {
        val existing=Plan(title="原有任务");store.savePlan(existing)
        val task=Plan(title="明晚练胸",domain=Domain.TRAINING,time="20:00",reminderMinutes=10)
        val first=store.writeAgentTasks("run-1",creates=listOf(task)).single()
        val repeated=store.writeAgentTasks("run-1",creates=listOf(task.copy(id="another-id"))).single()
        assertEquals(first.id,repeated.id);assertEquals(2,store.snapshot().plans.size)
        val moved=task.copy(time="21:00",priority=0)
        store.writeAgentTasks("run-1",changes=listOf(task to moved))
        assertEquals(listOf(task.id),store.snapshot().taskReceipt!!.createdIds)
        store.close();store=BojiStore(context,name)
        store.undoAgentTasks()
        assertEquals(listOf(existing),store.snapshot().plans);assertNull(store.snapshot().taskReceipt)
    }

    @Test fun agentTaskUpdateRestoresOriginalAndUndoRejectsLaterManualChanges() {
        val original=Plan(title="学习",time="19:00");store.savePlan(original)
        val updated=original.copy(time="20:00",priority=1)
        store.writeAgentTasks("update-1",changes=listOf(original to updated))
        store.undoAgentTasks();assertEquals(original,store.snapshot().plans.single())
        store.writeAgentTasks("update-2",changes=listOf(original to updated))
        val manual=updated.copy(title="手动新标题");store.savePlan(manual)
        assertTrue(runCatching {store.undoAgentTasks()}.isFailure)
        assertEquals(manual,store.snapshot().plans.single())
    }

    @Test fun invalidAgentBatchWritesNothingAndRecordPreventsUndoOfCreatedTask() {
        val valid=Plan(title="有效任务")
        assertTrue(runCatching {store.writeAgentTasks("bad",creates=listOf(valid,Plan(title="坏任务",time="27:00")))}.isFailure)
        assertTrue(store.snapshot().plans.isEmpty());assertNull(store.snapshot().taskReceipt)
        store.writeAgentTasks("good",creates=listOf(valid))
        store.saveRecord(LifeRecord(domain=valid.domain,title="学习记录",minutes=20,planId=valid.id))
        assertTrue(runCatching {store.undoAgentTasks()}.isFailure)
        assertEquals(1,store.snapshot().records.size);assertEquals(PlanStatus.DONE,store.snapshot().plans.single().status)
    }

    @Test fun similarTaskDoesNotSilentlyDropNewRequirementsAndNoopCannotUndoOlderRequest() {
        val task=Plan(title="练胸",time="20:00",expectedMinutes=40,reminderMinutes=10)
        store.writeAgentTasks("first",creates=listOf(task))
        assertTrue(runCatching {store.writeAgentTasks("conflict",creates=listOf(task.copy(id="new",expectedMinutes=60)))}.isFailure)
        assertEquals(task,store.snapshot().plans.single())
        val reused=store.writeAgentTasks("retry",creates=listOf(task.copy(id="retry-id"))).single()
        assertEquals(task.id,reused.id);assertNull(store.snapshot().taskReceipt)
        assertTrue(runCatching {store.undoAgentTasks()}.isFailure)
        assertEquals(task,store.snapshot().plans.single())
    }

    @Test fun childFirstBatchResolvesAnExistingParentBeforeDeduplication() {
        val parent=Plan(title="期末英语");store.savePlan(parent)
        val proposedParent=parent.copy(id="new-parent")
        val child=Plan(title="背单词",parentId=proposedParent.id)
        val result=store.writeAgentTasks("family",creates=listOf(child,proposedParent))
        assertEquals(parent.id,result[1].id)
        assertEquals(parent.id,store.snapshot().plans.single {it.id==child.id}.parentId)
        store.undoAgentTasks();assertEquals(listOf(parent),store.snapshot().plans)
    }

    @Test fun confirmationReceiptIsAtomicAndTaskReceiptKeepsItsConversationAnchor() {
        val record=LifeRecord(title="练胸",count=8)
        val batch=DraftBatch(records=listOf(record))
        assertTrue(runCatching {store.applyDraftWithMessage(batch,ChatMessage(user=false,text=""))}.isFailure)
        assertTrue(store.snapshot().records.isEmpty())
        val message=ChatMessage(user=false,text="已记录练胸，8次")
        store.applyDraftWithMessage(batch,message);store.applyDraftWithMessage(batch,message)
        assertEquals(1,store.snapshot().records.size);assertEquals(listOf(message),store.snapshot().messages)
        val task=Plan(title="复习")
        store.writeAgentTasks("anchored",creates=listOf(task));store.attachTaskReceipt(message.id)
        store.close();store=BojiStore(context,name)
        assertEquals(message.id,store.snapshot().taskReceipt!!.messageId)
        store.undoAgentTasks();assertTrue(store.snapshot().plans.isEmpty());assertEquals(1,store.snapshot().records.size)
    }
}
