package com.boji.app.data

import com.boji.app.model.*
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal data class StoredData(
    val records: List<LifeRecord> = emptyList(),
    val plans: List<Plan> = emptyList(),
    val goals: List<WeeklyGoal> = emptyList(),
    val views: List<SavedView> = emptyList(),
)

internal object DataValidation {
    const val MAX_MONEY_FEN = 999_999_999_999L

    fun id(value: String) = text(value, "标识", 128, required = true)

    private fun text(value: String, field: String, limit: Int, required: Boolean = false) {
        require(value.length <= limit && (!required || value.isNotBlank())) { "${field}长度不正确" }
        require(value.none { it == '\u0000' || it == '\u007f' || (it < ' ' && it != '\n' && it != '\t') }) {
            "${field}含无效字符"
        }
    }

    private fun date(value: String) {
        require(runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)) { "日期格式应为 YYYY-MM-DD" }
    }

    fun record(value: LifeRecord) {
        id(value.id); text(value.title, "记录名称", 120, true); date(value.date)
        require(value.minutes in 0..1440) { "时长应在 0 到 1440 分钟之间" }
        require(value.amountFen in 0..MAX_MONEY_FEN) { "金额超出范围" }
        if (value.domain == Domain.FINANCE) {
            require(value.amountFen > 0 && value.minutes == 0 && value.goalId == null) { "收支需填写正金额，且不能计入训练或学习目标" }
        } else {
            require(value.amountFen == 0L) { "训练和学习不填写金额" }
        }
        text(value.category, "分类", 60); text(value.note, "备注", 10_000)
        text(value.source, "来源", 60, true)
        value.goalId?.let(::id); value.planId?.let(::id); value.sourceId?.let(::id)
        require(value.createdAt.length <= 40 && runCatching { LocalDateTime.parse(value.createdAt) }.isSuccess) { "创建时间格式不正确" }
    }

    fun plan(value: Plan) {
        id(value.id); text(value.title, "计划名称", 120, true); date(value.date)
        require(value.expectedMinutes in 0..1440) { "计划时长应在 0 到 1440 分钟之间" }
        require(value.time.isEmpty() || (value.time.matches(Regex("\\d{2}:\\d{2}")) && runCatching { LocalTime.parse(value.time) }.isSuccess)) {
            "时间格式应为 HH:mm"
        }
        value.goalId?.let(::id); value.seriesId?.let(::id)
        require(value.domain != Domain.FINANCE || value.goalId == null) { "收支计划不能关联训练或学习目标" }
    }

    fun goal(value: WeeklyGoal) {
        id(value.id); text(value.title, "目标名称", 120, true)
        require(value.domain != Domain.FINANCE) { "每周目标用于训练或学习" }
        require(value.target > 0 && value.target <= if (value.mode == GoalMode.COUNT) 7 else 10080) {
            if (value.mode == GoalMode.COUNT) "每周次数应在 1 到 7 次之间" else "每周分钟数应在 1 到 10080 之间"
        }
        require(value.minimumMinutes in 0..1440) { "最低时长应在 0 到 1440 分钟之间" }
    }

    fun view(value: SavedView) { text(value.name, "视图名称", 40, true) }

    fun message(value: ChatMessage) { id(value.id); text(value.text, "消息", 20_000, true) }

    fun settings(value: ModelSettings) {
        text(value.memory, "记忆", 4000)
        require(value.connections.size in 1..10 && value.activeIndex in value.connections.indices) { "请选择有效模型" }
        require(value.monthlyBudgetFen in 0..MAX_MONEY_FEN) { "预算超出范围" }
        text(value.role, "助手角色", 60, true)
        value.connections.forEach { connection ->
            text(connection.name, "模型名称", 60, true); text(connection.endpoint, "模型地址", 2048)
            text(connection.model, "模型编号", 200); text(connection.apiKey, "模型密钥", 16384)
            if (connection.endpoint.isNotBlank()) {
                val uri = runCatching { URI(connection.endpoint) }.getOrNull()
                require(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
                    "模型地址需为有效的 HTTP 或 HTTPS 地址"
                }
            }
        }
        if (value.enabled) {
            val active = value.connections[value.activeIndex]
            require(active.endpoint.isNotBlank() && active.model.isNotBlank()) { "请填写模型地址和编号" }
        }
    }

    fun data(value: StoredData) {
        require(value.records.size <= 100_000 && value.plans.size <= 100_000 && value.goals.size <= 1000 && value.views.size <= 100) { "备份项目过多" }
        unique(value.records.map { it.id }, "记录"); unique(value.plans.map { it.id }, "计划")
        unique(value.goals.map { it.id }, "目标"); unique(value.views.map { it.name }, "视图")
        value.records.forEach(::record); value.plans.forEach(::plan); value.goals.forEach(::goal); value.views.forEach(::view)
        val goals = value.goals.associateBy { it.id }
        val plans = value.plans.associateBy { it.id }
        fun checkGoal(id: String?, domain: Domain) {
            if (id != null) require(goals[id]?.domain == domain) { "关联目标不存在或类型不同" }
        }
        value.plans.forEach { checkGoal(it.goalId, it.domain) }
        val linked = value.records.mapNotNull { it.planId }
        unique(linked, "计划完成记录")
        value.records.forEach { record ->
            checkGoal(record.goalId, record.domain)
            record.planId?.let { planId ->
                val plan = plans[planId]
                require(plan != null && plan.domain == record.domain) { "关联计划不存在或类型不同" }
                require(plan.status == PlanStatus.DONE) { "已有完成记录的计划必须为完成状态" }
            }
        }
        value.plans.forEach { plan ->
            require((plan.id in linked) == (plan.status == PlanStatus.DONE)) { "完成计划需要实际记录" }
        }
    }

    fun unique(values: List<String>, label: String) { require(values.distinct().size == values.size) { "${label}重复" } }
}
