package com.boji.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boji.app.data.BojiStore
import com.boji.app.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager

/** Run on an isolated emulator. Only rows created by this test are removed afterwards. */
@RunWith(AndroidJUnit4::class)
class MainFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var store: BojiStore
    private var before = emptySet<String>()
    private var beforePlans = emptySet<String>()
    private var beforeGoals = emptySet<String>()

    @Before fun snapshot() {
        store=BojiStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val snapshot=store.snapshot()
        before=snapshot.records.map { it.id }.toSet()
        beforePlans=snapshot.plans.map { it.id }.toSet()
        beforeGoals=snapshot.goals.map { it.id }.toSet()
        Assume.assumeFalse("Local assistant test needs cloud disabled",snapshot.settings.enabled)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("assistant-input").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanUp() {
        if (::store.isInitialized) {
            store.snapshot().records.filter { it.id !in before }.forEach { store.deleteRecord(it.id) }
            store.snapshot().plans.filter { it.id !in beforePlans }.forEach { store.deletePlan(it.id) }
            store.snapshot().goals.filter { it.id !in beforeGoals }.forEach { store.archiveGoal(it.id) }
            store.savePendingDraft(null)
            store.close()
        }
    }

    @Test fun planCompletionAndThreeDomainAssistantFlow() {
        val title="验收学习-${System.nanoTime().toString().takeLast(5)}"
        tab("日程")
        compose.onAllNodesWithText("加计划").onFirst().performClick()
        compose.onNodeWithText("要做什么").performTextInput(title)
        compose.onNodeWithText("更多选项").performClick()
        compose.onNodeWithText("预计时长（分钟）").performScrollTo().performTextReplacement("30")
        compose.onNodeWithText("保存计划").performScrollTo().performClick()
        compose.waitUntil(10_000) { store.snapshot().plans.any { it.title == title } }
        compose.waitUntil(10_000) {compose.onAllNodesWithText("要做什么").fetchSemanticsNodes().isEmpty()}
        val createdPlan=store.snapshot().plans.single {it.title==title}
        compose.onNodeWithTag("task-calendar").performScrollToNode(hasText(title))
        compose.onNodeWithTag("plan-complete-${createdPlan.id}").performScrollTo().performClick()
        try {compose.waitUntil(5000) {compose.onAllNodesWithText("时长（分钟）").fetchSemanticsNodes().isNotEmpty()}}
        catch(error:Throwable) {capture("qa-completion-failure");throw AssertionError(compose.onRoot(useUnmergedTree=true).printToString(),error)}
        compose.onNodeWithText("时长（分钟）").performTextReplacement("20")
        compose.onNodeWithText("保存",substring=false).performScrollTo().performClick()
        compose.waitUntil(10_000) { store.snapshot().records.any { it.title == title } }
        val saved=store.snapshot()
        val record=saved.records.single { it.title == title }
        assertEquals(20,record.minutes)
        assertEquals(PlanStatus.DONE,saved.plans.single { it.id == record.planId }.status)
        assertEquals(1,saved.records.count { it.planId == record.planId })

        tab("助手")
        compose.onNodeWithTag("assistant-input").performTextInput("今天练拳40分钟，晚饭28元，学Redis半小时")
        compose.onNodeWithTag("assistant-input").assertTextContains("今天练拳40分钟，晚饭28元，学Redis半小时")
        android.os.SystemClock.sleep(400)
        compose.onNodeWithTag("assistant-send").assertIsEnabled().performClick()
        try { compose.waitUntil(10_000) { store.snapshot().draft != null } }
        catch (error: Exception) { throw AssertionError(compose.onRoot(useUnmergedTree=true).printToString(),error) }
        closeKeyboard()
        capture("02-assistant-draft")
        assertEquals(before.size+1,store.snapshot().records.size)
        compose.onNodeWithText("核对并保存").performScrollTo().performClick()
        compose.onNodeWithText("确认保存").performScrollTo().performClick()
        compose.waitUntil(10_000) { store.snapshot().draft == null && store.snapshot().records.size == before.size+4 }
        val generated=store.snapshot().records.filter { it.id !in before && it.id != record.id }
        assertEquals(setOf(Domain.TRAINING,Domain.FINANCE,Domain.LEARNING),generated.map { it.domain }.toSet())
        assertEquals(2800L,generated.single { it.domain == Domain.FINANCE }.amountFen)

        tab("积累")
        compose.onNodeWithText("搜索记录").performTextInput("Redis")
        compose.onNodeWithContentDescription("切换表格").performClick()
        compose.onNodeWithText(generated.single { it.domain == Domain.LEARNING }.title).assertExists()
        closeKeyboard()
        capture("03-space-table")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("搜索记录").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before.size+4,store.snapshot().records.size)
        tab("日程")
        compose.onNodeWithContentDescription("${LocalDate.now()} 有记录或计划").assertExists()
        capture("04-calendar")
    }

    @Test fun weeklyGoalUsesActualRecordWithoutSecondCheckin() {
        val title="每周练拳-${System.nanoTime().toString().takeLast(4)}"
        tab("积累")
        compose.onNodeWithText("添加",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("目标",substring=false).performTextInput(title)
        compose.onNodeWithText("每次至少几分钟").performTextInput("20")
        compose.onNodeWithText("保存目标").performScrollTo().performClick()
        compose.waitUntil(10_000) { store.snapshot().goals.any { it.title == title } }
        val goal=store.snapshot().goals.single { it.title == title }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("record-fab").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("record-fab").performClick()
        compose.onNodeWithText("内容",substring=false).performTextInput("练拳")
        compose.onNodeWithText("时长（分钟）").performTextInput("40")
        compose.onAllNodes(hasText(title) and hasClickAction()).onLast().performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("保存",substring=false).performScrollTo().performClick()
        try { compose.waitUntil(10_000) { store.snapshot().records.any { it.goalId == goal.id } } }
        catch (failure: Throwable) { throw AssertionError("Records: ${store.snapshot().records}\n${compose.onRoot(useUnmergedTree=true).printToString()}",failure) }
        assertEquals(1,Progress.value(goal,store.snapshot().records,LocalDate.now()))
        compose.onNodeWithText("1 / 3 次",substring=true).assertExists()
        capture("01-today")
    }

    private fun closeKeyboard() {
        compose.runOnUiThread {
            compose.activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken,0)
        }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(400)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        closeKeyboard()
        // Compose semantics can settle before Android's sheet/IME compositor animation.
        android.os.SystemClock.sleep(500)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val file=File(instrumentation.targetContext.getExternalFilesDir(null),"screenshots/$name.png")
        file.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        }
    }

    private fun tab(text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
    }
}
