package com.boji.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boji.app.data.BojiStore
import com.boji.app.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CoreUserFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var store: BojiStore
    private lateinit var before: AppState
    private var oldDraft: ModelSettings?=null
    @Before fun setup() {
        store=BojiStore(InstrumentationRegistry.getInstrumentation().targetContext)
        before=store.snapshot();oldDraft=store.settingsDraft()
        Assume.assumeFalse(before.settings.enabled);Assume.assumeTrue(before.draft==null)
        compose.waitUntil(10000){compose.onAllNodesWithTag("assistant-input").fetchSemanticsNodes().isNotEmpty()}
    }
    @After fun cleanup() {
        if(::before.isInitialized) {
            store.snapshot().records.filter {r->before.records.none {it.id==r.id}}.forEach {store.deleteRecord(it.id)}
            store.snapshot().plans.filter {r->before.plans.none {it.id==r.id}}.forEach {store.deletePlan(it.id)}
            store.snapshot().memories.filter {r->before.memories.none {it.id==r.id}}.forEach {store.deleteMemory(it.id)}
            store.savePendingDraft(null);store.saveSettings(before.settings);store.saveSettingsDraft(oldDraft)
        }
        if(::store.isInitialized)store.close()
    }
    @Test fun modelDraftSurvivesNavigationAndActivityRecreation() {
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithTag("deepseek-key").performTextReplacement("UX_TEST_NOT_A_REAL_KEY")
        compose.waitUntil(10000){store.settingsDraft()?.connections?.firstOrNull()?.apiKey=="UX_TEST_NOT_A_REAL_KEY"}
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithText("记忆",substring=false).performClick()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("返回",substring=false).performClick()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithTag("deepseek-key").assertTextContains("UX_TEST_NOT_A_REAL_KEY")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000){compose.onAllNodesWithTag("deepseek-key").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("deepseek-key").assertTextContains("UX_TEST_NOT_A_REAL_KEY")
        compose.onNodeWithTag("connect-deepseek").assertIsDisplayed()
        compose.onNodeWithTag("settings-back").performClick()
    }
    @Test fun modelDraftCannotPretendThatInactiveReadPermissionsAreGranted() {
        lateinit var vm:BojiViewModel
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[BojiViewModel::class.java]}
        store.saveSettings(before.settings.copy(includeHistory=false,includeMemory=false))
        store.saveSettingsDraft(before.settings.copy(includeHistory=true,includeMemory=true,connections=listOf(
            com.boji.app.assistant.DeepSeekConnection.default("UX_SCOPE_DRAFT_TEST")),activeIndex=0))
        compose.runOnUiThread {vm.refresh()}
        compose.waitUntil(10000){vm.state.value.settingsDraft?.includeMemory==true && !vm.state.value.settings.includeMemory}
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithTag("deepseek-key").assertTextContains("UX_SCOPE_DRAFT_TEST")
        compose.onNodeWithText("使用范围").performClick()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
        compose.onAllNodes(isToggleable())[2].assertIsOff()
        assertFalse(store.snapshot().settings.includeHistory);assertFalse(store.snapshot().settings.includeMemory)
        compose.onNodeWithTag("settings-back").performClick()
    }
    @Test fun editingTaskAndCorrectedDraftSurviveRecreation() {
        lateinit var vm:BojiViewModel
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[BojiViewModel::class.java]}
        val task=Plan(title="旋转保留任务",priority=1)
        vm.savePlan(task)
        compose.waitUntil(10000){vm.state.value.plans.any {it.id==task.id}}
        compose.runOnUiThread {vm.openTask(task.id)}
        compose.onNodeWithText("要做什么").performTextReplacement("修改后任务")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("要做什么").assertTextContains("修改后任务")
        compose.onNodeWithText("保存计划").performScrollTo().performClick()
        compose.waitUntil(10000){store.snapshot().plans.any {it.id==task.id && it.title=="修改后任务"}}
        val draft=DraftBatch(records=listOf(LifeRecord(title="练胸",count=7)))
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[BojiViewModel::class.java];vm.saveDraft(draft)}
        compose.waitUntil(10000){vm.state.value.draft!=null}
        tab("助手");compose.onNodeWithText("核对并保存").performScrollTo().performClick()
        compose.onNode(hasText("练胸",substring=true) and hasClickAction()).performClick()
        compose.onNodeWithText("实际数量").performTextReplacement("8")
        // Platform IME animations run outside Compose's test clock.
        android.os.SystemClock.sleep(400)
        compose.onNodeWithText("保存",substring=false).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10000){store.snapshot().draft?.records?.singleOrNull()?.count==8}
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("确认保存").performScrollTo().performClick()
        compose.waitUntil(10000){store.snapshot().records.any {it.id==draft.records.single().id && it.count==8}}
    }
    @Test fun sevenTimesAndPhotoLinkToTaskThenAppearInBothViews() {
        lateinit var vm: BojiViewModel
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[BojiViewModel::class.java]}
        val plan=Plan(title="验收练胸",domain=Domain.TRAINING,priority=0,deadline=java.time.LocalDate.now().toString())
        vm.savePlan(plan)
        compose.waitUntil(10000){store.snapshot().plans.any {it.id==plan.id}}
        val photo=File(compose.activity.cacheDir,"test-source.jpg")
        Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.GRAY);photo.outputStream().use {compress(Bitmap.CompressFormat.JPEG,90,it)};recycle()}
        androidx.exifinterface.media.ExifInterface(photo).apply {setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}
        vm.attachImage(Uri.fromFile(photo))
        compose.waitUntil(10000){vm.state.value.pendingImage!=null}
        android.graphics.BitmapFactory.decodeFile(vm.state.value.pendingImage).let {assertEquals(40,it.width);assertEquals(80,it.height);it.recycle()}
        tab("助手")
        compose.onNodeWithTag("assistant-input").performTextInput("今天练胸练了七次")
        compose.onNodeWithTag("assistant-input").performImeAction()
        try { compose.waitUntil(10000){store.snapshot().draft!=null} }
        catch (failure: Throwable) { throw AssertionError("State: ${vm.state.value}\n${compose.onRoot(useUnmergedTree=true).printToString()}",failure) }
        val draft=store.snapshot().draft!!;assertEquals(7,draft.records.single().count)
        assertEquals("次",draft.records.single().countUnit);assertEquals(plan.id,draft.records.single().planId)
        assertEquals(1,draft.records.single().attachments.size)
        assertTrue(store.snapshot().records.none {it.planId==plan.id})
        compose.onNodeWithText("核对并保存").performScrollTo().performClick()
        compose.onNodeWithContentDescription("记录照片").assertExists()
        compose.onNodeWithText("保存后完成：${plan.title}").assertExists()
        compose.onNodeWithText("确认保存").performScrollTo().performClick()
        compose.waitUntil(10000){store.snapshot().records.any {it.planId==plan.id}}
        assertEquals(PlanStatus.DONE,store.snapshot().plans.find {it.id==plan.id}!!.status)
        tab("日程")
        compose.onNodeWithText("多维表格").performClick()
        compose.onNodeWithText(plan.title).assertExists()
        compose.onNodeWithText("已完成",substring=false).assertExists()
        compose.runOnUiThread {compose.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        try {
            compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            compose.waitUntil(10000){compose.onAllNodesWithText("搜索任务或项目").fetchSemanticsNodes().isEmpty()}
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            android.os.SystemClock.sleep(600)
            compose.waitForIdle()
            val row=compose.onNodeWithTag("task-${plan.id}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Landscape table must show at least 48 dp of the task row",row.height>=48*compose.activity.resources.displayMetrics.density)
            val config=compose.activity.resources.configuration
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("stream",
                "\nLANDSCAPE: ${config.screenWidthDp}x${config.screenHeightDp} dp, font ${config.fontScale}, visible row ${row.height/compose.activity.resources.displayMetrics.density} dp\n")})
            val capture=File(compose.activity.getExternalFilesDir(null),"screenshots/12-landscape-task-row.png").apply {parentFile!!.mkdirs()}
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                capture.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        } finally {
            compose.runOnUiThread {compose.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}
        }
        compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(400)
        compose.waitForIdle()
        compose.onNode(hasText("日历") and hasClickAction() and isSelectable()).performClick()
        compose.onNodeWithTag("task-table").assertDoesNotExist()
        compose.onNodeWithTag("task-calendar").performScrollToNode(hasText(plan.title))
        compose.onNodeWithText(plan.title).assertExists()
        photo.delete()
    }
    @Test fun explicitMemoryCanBeSavedEditedAndForgottenWithoutModelConfiguration() {
        tab("助手");compose.onNodeWithTag("assistant-input").performTextInput("记住：周二晚上不能学习")
        compose.onNodeWithTag("assistant-input").performImeAction()
        compose.waitUntil(10000){store.snapshot().draft?.memories?.isNotEmpty()==true}
        compose.onNodeWithText("核对并保存").performScrollTo().performClick()
        compose.onNodeWithText("确认保存").performScrollTo().performClick()
        compose.waitUntil(10000){store.snapshot().memories.any {it.text=="周二晚上不能学习"}}
        compose.onNodeWithText("记忆",substring=false).performClick()
        compose.onNodeWithText("周二晚上不能学习").assertExists()
        compose.onNodeWithText("修改",substring=false).performClick()
        compose.onNodeWithText("记住什么").performTextReplacement("周三晚上不能学习")
        compose.onNodeWithText("保存",substring=false).performClick()
        compose.waitUntil(10000){store.snapshot().memories.any {it.text=="周三晚上不能学习"}}
        compose.onNodeWithText("遗忘",substring=false).performClick()
        compose.onAllNodesWithText("遗忘",substring=false).onLast().performClick()
        compose.waitUntil(10000){store.snapshot().memories.none {it.text.contains("晚上不能学习")}}
    }
    private fun tab(label:String) {compose.onAllNodes(hasText(label) and hasClickAction()).onFirst().performClick();compose.waitForIdle()}
}
