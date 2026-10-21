package com.boji.app

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boji.app.data.BojiStore
import com.boji.app.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Real Compose interactions; fixtures only belong to this test invocation. */
@RunWith(AndroidJUnit4::class)
class CoreExperienceV03Test {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var store:BojiStore
    private lateinit var vm:BojiViewModel
    private val prefix="QA-v03-${System.nanoTime()}"
    private val ids=mutableListOf<String>()
    private val viewName="$prefix-view"
    private val receiptRun="$prefix-navigation"
    private var retryDraftId:String?=null

    @Before fun setup() {
        store=BojiStore(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.waitUntil(10000){compose.onAllNodesWithTag("assistant-input").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnUiThread {vm=ViewModelProvider(compose.activity)[BojiViewModel::class.java]}
    }
    @After fun cleanup() {
        if(::store.isInitialized) {
            if(store.snapshot().taskReceipt?.id==receiptRun)store.undoAgentTasks()
            if(retryDraftId!=null && store.snapshot().draft?.id==retryDraftId)store.savePendingDraft(null)
            ids.asReversed().forEach(store::deletePlan)
            store.deleteView(viewName)
            store.close()
        }
    }

    @Test fun failedLatestMessageCanRetryAndAcceptedInputClearsAfterFastLocalReply() {
        Assume.assumeFalse(store.snapshot().settings.enabled)
        Assume.assumeTrue(store.snapshot().draft==null)
        store.saveMessage(ChatMessage(user=true,text="今天练拳40分钟"))
        val failed=ChatMessage(user=false,text="网络连接失败，请重试",failed=true)
        store.saveMessage(failed)
        compose.runOnUiThread {vm.refresh()}
        compose.waitUntil(10000) {vm.state.value.messages.lastOrNull()?.id==failed.id}
        compose.onNodeWithTag("assistant-retry").performScrollTo().performClick()
        compose.waitUntil(10000) {vm.state.value.draft!=null && !vm.state.value.assistantBusy}
        retryDraftId=vm.state.value.draft!!.id
        assertEquals(40,vm.state.value.draft!!.records.single().minutes)
        compose.waitUntil(10000) {compose.onNodeWithTag("assistant-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty()}
        compose.onNodeWithTag("assistant-retry").assertDoesNotExist()
    }

    @Test fun futureCalendarDateIsInheritedAndAdvancedFieldsStartCollapsed() {
        val future=LocalDate.now().plusDays(2)
        tab("日程")
        val months=ChronoUnit.MONTHS.between(YearMonth.now(),YearMonth.from(future)).toInt()
        repeat(months){compose.onNodeWithContentDescription("下个月").performClick()}
        compose.onNodeWithContentDescription(future.toString(),substring=true).performScrollTo().performClick()
        compose.onNodeWithContentDescription(future.toString(),substring=true).assertTextContains(future.dayOfMonth.toString())
        compose.onAllNodesWithText("加计划").onFirst().performClick()
        compose.onNodeWithText("日期",substring=false).assertTextContains(future.toString())
        compose.onNodeWithText("更多选项").assertIsDisplayed()
        compose.onNodeWithText("截止时间",substring=false).assertDoesNotExist()
        compose.onNodeWithText("优先级",substring=false).assertDoesNotExist()
        compose.onNodeWithText("项目",substring=false).assertDoesNotExist()
        compose.onNodeWithText("要做什么").performTextInput("$prefix-future")
        compose.onNodeWithText("保存计划").performScrollTo().performClick()
        compose.waitUntil(10000){store.snapshot().plans.any {it.title=="$prefix-future"}}
        val saved=store.snapshot().plans.single {it.title=="$prefix-future"};ids+=saved.id
        assertEquals(future.toString(),saved.date)
        compose.onNodeWithText("要做什么").assertDoesNotExist()
    }

    @Test fun failedTaskSaveKeepsEditorAndInputThenCanRetry() {
        val parent=Plan(title="$prefix-parent")
        val child=Plan(title="$prefix-child",parentId=parent.id)
        ids+=listOf(parent.id,child.id)
        store.savePlans(listOf(parent,child))
        compose.runOnUiThread {vm.refresh()}
        compose.waitUntil(10000){vm.state.value.plans.any {it.id==child.id}}
        compose.runOnUiThread {vm.openTask(child.id)}
        compose.onNodeWithText("要做什么").performTextReplacement("$prefix-corrected")
        // The open editor retains its original parent; concurrent storage deletion invalidates it.
        store.deletePlan(parent.id)
        compose.onNodeWithText("保存计划").performScrollTo().performClick()
        compose.waitUntil(10000){vm.state.value.saveError=="父任务不存在" && !vm.state.value.saving}
        compose.onNodeWithText("要做什么").assertTextContains("$prefix-corrected")
        compose.onNodeWithText("编辑计划",substring=false).assertExists()
        compose.onAllNodesWithText("父任务不存在").onFirst().assertExists()
        compose.onNodeWithText("保存计划").assertIsEnabled()
        assertEquals(child.title,store.snapshot().plans.single {it.id==child.id}.title)
        store.savePlan(parent)
        compose.onNodeWithText("保存计划").performClick()
        compose.waitUntil(10000){store.snapshot().plans.any {it.id==child.id && it.title=="$prefix-corrected"} && !vm.state.value.saving}
        compose.onNodeWithText("要做什么").assertDoesNotExist()
        assertEquals(parent.id,store.snapshot().plans.single {it.id==child.id}.parentId)
    }

    @Test fun savedTaskViewRestoresSearchAndModeAndCanBeDeleted() {
        val match=Plan(title="$prefix-match")
        val other=Plan(title="$prefix-other")
        ids+=listOf(match.id,other.id)
        store.savePlans(listOf(match,other))
        compose.runOnUiThread {vm.refresh()}
        compose.waitUntil(10000){vm.state.value.plans.any {it.id==match.id}}
        tab("日程")
        compose.onNodeWithText("多维表格",substring=false).performClick()
        compose.onNodeWithText("搜索任务或项目").performTextInput("$prefix-match")
        viewMenu()
        compose.onNodeWithText("保存视图",substring=false).performClick()
        compose.onNodeWithText("视图名称").performTextInput(viewName)
        compose.onNodeWithText("保存",substring=false).performClick()
        compose.waitUntil(10000){store.snapshot().views.any {it.name==viewName}}
        val saved=store.snapshot().views.single {it.name==viewName}
        assertEquals("$prefix-match",saved.query);assertEquals("table",saved.viewMode)
        compose.onNodeWithText("搜索任务或项目").performTextReplacement("$prefix-other")
        compose.onNode(hasText("日历") and hasClickAction() and isSelectable()).performClick()
        compose.onNodeWithTag("task-calendar").assertExists()
        viewMenu()
        compose.onNodeWithText("使用：$viewName").performScrollTo().performClick()
        compose.onNodeWithTag("task-table").assertExists()
        compose.onNodeWithText("搜索任务或项目").assertTextContains("$prefix-match")
        compose.onNodeWithTag("task-${match.id}").assertExists()
        compose.onNodeWithTag("task-${other.id}").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("task-table").assertExists()
        compose.onNodeWithText("搜索任务或项目").assertTextContains("$prefix-match")
        viewMenu()
        compose.onNode(hasText("删除") and hasAnySibling(hasText("使用：$viewName"))).performScrollTo().performClick()
        compose.waitUntil(10000){store.snapshot().views.none {it.name==viewName}}
        compose.onNodeWithText("使用：$viewName").assertDoesNotExist()
        compose.onNodeWithText("完成",substring=false).performClick()
        compose.onNodeWithText("清空筛选",substring=false).performClick()
        compose.onNodeWithText("搜索任务或项目").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        compose.onNodeWithTag("task-${match.id}").assertExists()
        compose.onNodeWithTag("task-${other.id}").assertExists()
    }

    @Test fun receiptNavigationClearsOldViewAndIsConsumedAfterOpeningTargetDate() {
        val future=LocalDate.now().plusDays(2)
        val task=Plan(title="$prefix-receipt-task",date=future.toString())
        ids+=task.id
        tab("日程")
        compose.onNodeWithText("多维表格",substring=false).performClick()
        compose.onNodeWithText("搜索任务或项目").performTextInput("$prefix-unmatched-filter")
        compose.onNodeWithTag("task-${task.id}").assertDoesNotExist()
        store.writeAgentTasks(receiptRun,creates=listOf(task))
        compose.runOnUiThread {vm.refresh()}
        compose.waitUntil(10000){vm.state.value.taskReceipt?.id==receiptRun}
        tab("助手")
        compose.onNodeWithText("查看日程",substring=false).performScrollTo().performClick()
        compose.onNodeWithTag("task-table").assertDoesNotExist()
        compose.onNodeWithTag("task-calendar").assertExists()
        compose.onNodeWithText("搜索任务或项目").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        compose.onNodeWithTag("task-calendar").performScrollToNode(hasText(task.title))
        compose.onNodeWithText(task.title,substring=false).assertIsDisplayed()
        val other=future.plusDays(1)
        compose.onNodeWithTag("task-calendar").performScrollToIndex(0)
        repeat(ChronoUnit.MONTHS.between(YearMonth.from(future),YearMonth.from(other)).toInt()) {
            compose.onNodeWithContentDescription("下个月").performClick()
        }
        compose.onNodeWithContentDescription(other.toString(),substring=true).performScrollTo().performClick()
        tab("助手");tab("日程")
        val section="${other.monthValue}月${other.dayOfMonth}日"
        compose.onNodeWithTag("task-calendar").performScrollToNode(hasText(section))
        compose.onNodeWithText(section,substring=false).assertIsDisplayed()
        compose.onNodeWithText(task.title,substring=false).assertDoesNotExist()
    }

    private fun viewMenu(){compose.onNode(hasText("视图",substring=true) and hasClickAction()).performClick()}
    private fun tab(label:String){compose.onNode(hasText(label) and hasClickAction()).performClick();compose.waitForIdle()}
}
