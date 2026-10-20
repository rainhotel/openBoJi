package com.boji.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boji.app.assistant.DeepSeekConnection
import com.boji.app.data.BojiStore
import com.boji.app.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Explicitly opted-in paid-provider acceptance. Credentials arrive on stdin through run-as, never arguments. */
@RunWith(AndroidJUnit4::class)
class LiveAssistantFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun realProviderCreatesUpdatesClarifiesAndPlansThroughThePhone() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveDeepSeek")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val keyFile=File(context.filesDir,"live-deepseek-key")
        val key=keyFile.readText().trim().also {keyFile.delete()}
        require(key.startsWith("sk-") && key.length>20) {"Missing live debug credential"}
        val store=BojiStore(context)
        val before=store.snapshot();val previousDraft=store.settingsDraft()
        Assume.assumeTrue("Keep existing pending draft",before.draft==null)
        val marker="云端验收"+UUID.randomUUID().toString().take(5)
        lateinit var vm:BojiViewModel
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[BojiViewModel::class.java]}
        compose.waitUntil(15000) {!vm.state.value.loading}
        try {
            vm.clearAgentSession()
            vm.saveSettings(before.settings.copy(enabled=false,includeHistory=true,includeMemory=true))
            compose.waitUntil(15000) {vm.state.value.settings.includeHistory && vm.state.value.settings.includeMemory}
            store.saveSettingsDraft(null);vm.refresh()
            compose.waitUntil(15000) {vm.state.value.settingsDraft==null}
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithTag("deepseek-key").performTextReplacement(key)
            compose.onNodeWithTag("connect-deepseek").performClick()
            compose.waitUntil(15000) {vm.state.value.connectionBusy}
            compose.waitUntil(120000) {!vm.state.value.connectionBusy}
            assertTrue("Official connection/tool check must activate",vm.state.value.settings.enabled)
            assertTrue("Official model discovery should declare vision",vm.state.value.settings.connections.single().supportsImages)
            report("Official models + two-turn tool connection check passed")
            compose.onNodeWithTag("settings-back").performClick()
            kotlinx.coroutines.runBlocking {DeepSeekConnection.check(vm.state.value.settings.connections.single().copy(thinking=true))}
            report("Thinking-mode two-turn tool replay passed")
            fun ask(text:String) {
                compose.onNodeWithTag("assistant-input").performTextReplacement(text)
                compose.onNodeWithTag("assistant-input").performImeAction()
                compose.waitUntil(15000) {vm.state.value.assistantBusy}
                compose.waitUntil(210000) {!vm.state.value.assistantBusy}
                assertNull("Native/UI operation must succeed",vm.state.value.error)
            }
            val tomorrow=LocalDate.now().plusDays(1).toString()
            ask("帮我创建明天晚上八点的${marker}练胸，40分钟，提前10分钟提醒，P1。直接安排。")
            val task=store.snapshot().plans.singleOrNull {it.title.contains(marker)}
            assertNotNull("A real task must be created without draft confirmation",task)
            task!!
            assertEquals(tomorrow,task.date);assertEquals("20:00",task.time);assertEquals(40,task.expectedMinutes)
            assertEquals(1,task.priority);assertEquals(10,task.reminderMinutes)
            assertNull("Direct task creation leaves no pending draft",store.snapshot().draft)
            assertNotNull("Task operation must have undo receipt",store.snapshot().taskReceipt)
            report("Chat directly created dated task with duration, priority and reminder")
            capture("09-live-created")
            ask("把刚才的${marker}练胸改到后天晚上九点，改为P0，其他不变。")
            val changed=store.snapshot().plans.single {it.id==task.id}
            assertEquals(LocalDate.now().plusDays(2).toString(),changed.date);assertEquals("21:00",changed.time)
            assertEquals(0,changed.priority);assertEquals(40,changed.expectedMinutes);assertEquals(10,changed.reminderMinutes)
            assertEquals(1,store.snapshot().plans.count {it.title.contains(marker)})
            compose.onNodeWithText("撤销上次操作").performScrollTo().performClick()
            compose.waitUntil(15000) {store.snapshot().plans.find {it.id==task.id}==task}
            assertEquals(task,store.snapshot().plans.single {it.id==task.id})
            report("Follow-up updated original task; undo restored it")
            val count=store.snapshot().plans.size
            ask("三号考${marker}英语、五号考数学，帮我安排复习。")
            assertEquals("Ambiguous month/year must not create tasks",count,store.snapshot().plans.size)
            assertNull(store.snapshot().draft)
            report("Ambiguous exam month/year was clarified without writes")
            val start=LocalDate.now().plusDays(3)
            ask("另一个安排：从${start}开始，连续3天，每天19:00学习${marker}英语单词30分钟，提前5分钟提醒，请直接创建这三天的任务。")
            val lessons=store.snapshot().plans.filter {it.title.contains(marker) && it.domain==Domain.LEARNING}
            assertEquals(3,lessons.size)
            assertEquals((0L..2L).map {start.plusDays(it).toString()}.toSet(),lessons.map {it.date}.toSet())
            assertTrue(lessons.all {it.time=="19:00" && it.expectedMinutes==30 && it.reminderMinutes==5})
            report("Three dated study tasks and reminders created through native Pi tools")
            compose.onAllNodes(hasText("日程") and hasClickAction()).onFirst().performClick()
            compose.onNodeWithText("多维表格").performClick()
            compose.onNodeWithText("搜索任务或项目").performTextReplacement(marker)
            compose.onNodeWithTag("task-table").performScrollToNode(hasText(task.title))
            compose.onNodeWithText(task.title).assertExists()
            capture("10-live-table")
            compose.onAllNodes(hasText("助手") and hasClickAction()).onFirst().performClick()
            val todayTask=Plan(title="$marker 今日练胸",domain=Domain.TRAINING)
            vm.savePlan(todayTask)
            compose.waitUntil(15000) {vm.state.value.plans.any {it.id==todayTask.id}}
            val source=File(context.cacheDir,"live-synthetic-photo.jpg")
            android.graphics.Bitmap.createBitmap(240,160,android.graphics.Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.LTGRAY)
                val paint=android.graphics.Paint().apply {color=android.graphics.Color.BLACK;textSize=26f}
                android.graphics.Canvas(this).drawText("Boji QA workout",12f,80f,paint)
                source.outputStream().use {compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it)};recycle()
            }
            vm.attachImage(android.net.Uri.fromFile(source))
            compose.waitUntil(15000) {vm.state.value.pendingImage!=null}
            ask("今天的${marker}练胸已经完成，练了七次，请记录并关联今天这项任务。照片是这次训练的附件。")
            val draft=store.snapshot().draft
            assertNotNull("Real model must stage workout record",draft)
            assertEquals(7,draft!!.records.single().count);assertEquals("次",draft.records.single().countUnit)
            assertEquals(todayTask.id,draft.records.single().planId);assertEquals(1,draft.records.single().attachments.size)
            assertTrue(store.snapshot().records.none {it.planId==todayTask.id})
            ask("刚才记录的数量改为八次，其他不变，仍然只是待确认记录。")
            val corrected=store.snapshot().draft!!.records.single()
            assertEquals(8,corrected.count);assertEquals(todayTask.id,corrected.planId);assertEquals(1,corrected.attachments.size)
            compose.onNodeWithTag("chat-confirm-draft").performScrollTo().performClick()
            compose.waitUntil(15000) {store.snapshot().records.any {it.planId==todayTask.id}}
            assertEquals(8,store.snapshot().records.single {it.planId==todayTask.id}.count)
            assertEquals(PlanStatus.DONE,store.snapshot().plans.single {it.id==todayTask.id}.status)
            compose.waitUntil(15000) {vm.state.value.messages.lastOrNull()?.text?.contains("8次")==true && !vm.state.value.saving}
            assertTrue(store.snapshot().messages.last().text.startsWith("已记录"))
            report("Real image request + seven-to-eight correction + one-tap record confirmation passed")
            capture("11-live-workout")
            ask("记住：${marker}期间，我每周二晚上不能学习。")
            assertTrue(store.snapshot().draft?.memories?.any {it.text.contains(marker)}==true)
            compose.onNodeWithTag("chat-confirm-draft").performScrollTo().performClick()
            compose.waitUntil(15000) {store.snapshot().memories.any {it.text.contains(marker)}}
            report("Explicit long-term memory was proposed and saved with one tap")
            source.delete()
            assertTrue("Existing schedule is retained",before.plans.all {old ->store.snapshot().plans.any {it==old}})
        } finally {
            vm.cancelAssistant();vm.cancelConnection()
            // Scoped cleanup: remove only this synthetic marker, preserve real app data/settings.
            store.snapshot().records.filter {it.title.contains(marker)}.forEach {store.deleteRecord(it.id)}
            store.snapshot().plans.filter {it.title.contains(marker)}.forEach {store.deletePlan(it.id)}
            store.snapshot().memories.filter {it.text.contains(marker)}.forEach {store.deleteMemory(it.id)}
            store.savePendingDraft(before.draft);store.saveSettings(before.settings);store.saveSettingsDraft(previousDraft)
            vm.clearAgentSession();store.close()
        }
    }
    private fun report(step:String) {InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("stream","\nLIVE: $step\n")})}
    private fun capture(name:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(500)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"screenshots/$name.png").apply {parentFile!!.mkdirs()}
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {image ->
            file.outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }
    }
}
