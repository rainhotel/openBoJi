package com.boji.app.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class Domain(val label: String) { TRAINING("训练"), FINANCE("收支"), LEARNING("学习") }
enum class MoneyKind(val label: String) { EXPENSE("支出"), INCOME("收入"), TRANSFER("转账"), REFUND("退款") }
enum class GoalMode(val label: String) { COUNT("每周次数"), MINUTES("每周分钟") }
enum class PlanStatus(val label: String) { TODO("待办"), DONE("完成"), SKIPPED("跳过") }

data class LifeRecord(
    val id: String = UUID.randomUUID().toString(),
    val domain: Domain = Domain.TRAINING,
    val title: String = "",
    val date: String = LocalDate.now().toString(),
    val minutes: Int = 0,
    val amountFen: Long = 0,
    val moneyKind: MoneyKind = MoneyKind.EXPENSE,
    val category: String = "",
    val note: String = "",
    val goalId: String? = null,
    val planId: String? = null,
    val source: String = "手动",
    val sourceId: String? = null,
    val createdAt: String = LocalDateTime.now().toString(),
)

data class Plan(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val domain: Domain = Domain.LEARNING,
    val date: String = LocalDate.now().toString(),
    val time: String = "",
    val expectedMinutes: Int = 25,
    val status: PlanStatus = PlanStatus.TODO,
    val goalId: String? = null,
    val seriesId: String? = null,
)

data class WeeklyGoal(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val domain: Domain = Domain.TRAINING,
    val mode: GoalMode = GoalMode.COUNT,
    val target: Int = 3,
    val minimumMinutes: Int = 0,
    val archived: Boolean = false,
)

data class ModelConnection(
    val name: String = "默认模型",
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val supportsImages: Boolean = false,
)
data class ModelSettings(
    val enabled: Boolean = false,
    val activeIndex: Int = 0,
    val connections: List<ModelConnection> = listOf(ModelConnection(), ModelConnection(name = "备用模型")),
    val includeHistory: Boolean = false,
    val includeFinance: Boolean = false,
    val role: String = "综合",
    val monthlyBudgetFen: Long = 0,
    val memory: String = "",
)

data class DraftBatch(
    val id: String = UUID.randomUUID().toString(),
    val records: List<LifeRecord> = emptyList(),
    val plans: List<Plan> = emptyList(),
)
data class ChatMessage(val id: String = UUID.randomUUID().toString(), val user: Boolean, val text: String)
data class InstalledSkill(val id: String, val name: String)
data class SavedView(val name: String, val domain: Domain? = null, val thisWeek: Boolean = false)
data class AppState(
    val loading: Boolean = true,
    val today: String = LocalDate.now().toString(),
    val records: List<LifeRecord> = emptyList(),
    val plans: List<Plan> = emptyList(),
    val goals: List<WeeklyGoal> = emptyList(),
    val settings: ModelSettings = ModelSettings(),
    val views: List<SavedView> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val draft: DraftBatch? = null,
    val assistantBusy: Boolean = false,
    val assistantStatus: String = "",
    val installedSkills: List<InstalledSkill> = emptyList(),
    val pendingImage: String? = null,
    val notice: String? = null,
    val error: String? = null,
    val voiceText: String? = null,
)

interface AppActions {
    fun saveRecord(record: LifeRecord)
    fun deleteRecord(id: String)
    fun savePlan(plan: Plan, repeatDays: Set<Int> = emptySet())
    fun skipPlan(id: String)
    fun deletePlan(id: String)
    fun saveGoal(goal: WeeklyGoal)
    fun archiveGoal(id: String)
    fun saveSettings(settings: ModelSettings)
    fun saveView(view: SavedView)
    fun deleteView(name: String)
    fun sendMessage(text: String)
    fun cancelAssistant()
    fun confirmDraft(batch: DraftBatch)
    fun dismissDraft()
    fun selectImage()
    fun removeImage()
    fun voiceInput()
    fun consumeVoice()
    fun clearNotice()
    fun exportBackup(password: String)
    fun importBackup(password: String)
    fun exportCsv()
    fun importSkill() {}
    fun clearAgentSession() {}
    fun removeSkill(id: String) {}
}

object Amounts {
    fun parseFen(text: String): Long {
        require(text.matches(Regex("[0-9]{1,10}(\\.[0-9]{1,2})?"))) { "金额最多两位小数" }
        return BigDecimal(text).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    }
    fun yuan(fen: Long): String = BigDecimal.valueOf(fen, 2).setScale(2).toPlainString()
}

object Progress {
    fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())
    fun value(goal: WeeklyGoal, records: List<LifeRecord>, date: LocalDate): Int {
        val start = weekStart(date)
        val rows = records.distinctBy { it.id }.filter {
            it.goalId == goal.id && it.domain == goal.domain &&
                runCatching { LocalDate.parse(it.date) }.getOrNull()?.let { d -> !d.isBefore(start) && d.isBefore(start.plusDays(7)) } == true
        }
        return if (goal.mode == GoalMode.MINUTES) rows.sumOf { it.minutes }
        else rows.filter { it.minutes > 0 && it.minutes >= goal.minimumMinutes }.map { it.date }.distinct().size
    }
    fun netExpense(records: List<LifeRecord>): Long = records.filter { it.domain == Domain.FINANCE }.sumOf {
        when (it.moneyKind) { MoneyKind.EXPENSE -> it.amountFen; MoneyKind.REFUND -> -it.amountFen; else -> 0 }
    }
}
