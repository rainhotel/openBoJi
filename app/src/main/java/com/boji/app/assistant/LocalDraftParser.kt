package com.boji.app.assistant

import com.boji.app.model.Amounts
import com.boji.app.model.AppState
import com.boji.app.model.Domain
import com.boji.app.model.DraftBatch
import com.boji.app.model.LifeRecord
import com.boji.app.model.MoneyKind
import com.boji.app.model.Plan
import com.boji.app.model.Progress
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** Deliberately small offline grammar: explicit quantities only, no probabilistic inference. */
internal object LocalDraftParser {
    private val training = Regex("练拳|拳击|搏击|跑步|健身|训练|运动|游泳|骑行|骑车|跳绳|俯卧撑|瑜伽|走路|散步")
    private val learning = Regex("学习|学(?=[A-Za-z\\p{IsHan}])|读书|看书|阅读|写代码|刷题|复习|背单词|研究")
    private val finance = Regex("花了|花费|支出|消费|购买|买|付款|晚饭|午饭|早饭|早餐|午餐|晚餐|工资|收入|到账|收款|收到|退款|退了|返还|转账|转了|转给")
    private val amount = Regex("(?<![-\\d.])([0-9]{1,10}(?:\\.[0-9]{1,2})?)(?:元|块钱?)")
    private val duration = Regex("([0-9]+(?:\\.[0-9]+)?|[一二两三四五六七八九十百]+)(?:个)?(半小时|小时|分钟)|半(?:个)?小时")
    private val absoluteDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val time = Regex("(?<![0-9])([0-2][0-9]:[0-5][0-9])(?![0-9])")

    fun reply(text: String, state: AppState, today: LocalDate): AssistantReply {
        if (Regex("统计|总结|查看|多少|复盘").containsMatchIn(text) && !Regex("记一|记录|记下").containsMatchIn(text)) {
            return summary(text, state, today)
        }
        if (Regex("[?？]|怎么|如何|吗|假如|假设|比如|例如|如果|建议|应该|可不可以|能不能").containsMatchIn(text)) {
            return AssistantReply("云端模型未启用。本地可整理明确记录，例如：练拳40分钟，晚饭28元。")
        }
        val draft = parse(text, today)
        return if (draft == null) AssistantReply("本地未生成草稿。请写清项目、时长或金额；安排计划时写上日期。")
        else AssistantReply("本地整理完成，请核对后保存。", draft)
    }

    fun parse(text: String, today: LocalDate): DraftBatch? = runCatching {
        require(text.length in 1..8000)
        require(!Regex("[?？]|怎么|如何|吗|假如|假设|比如|例如|如果|应该|建议|大概|可能|左右|差不多|或者|还是|没|未|取消|删除|不要").containsMatchIn(text))
        // Weekly frequency and relative weekdays need scheduling logic, never an invented date.
        require(!Regex("每[天日周月]|下周|上周|本周|这周|周[一二三四五六日天末]|星期|礼拜|月底|月初|这几天|前几天|上个月|明年|去年|[天周月年][前后]|[0-9]{1,2}[月日号]|[0-9]{1,4}/[0-9]").containsMatchIn(text))
        val clauses = text.trim().split(Regex("[，,；;。\\n]+" )).map { it.trim() }.filter { it.isNotBlank() }
        require(clauses.size in 1..20)
        val dates = dateTokens(text, today).distinct()
        val commonDate = if (dates.size == 1) dates.single() else today
        val records = mutableListOf<LifeRecord>()
        val plans = mutableListOf<Plan>()
        for (clause in clauses) {
            require(!Regex("[-负]\\s*[0-9]").containsMatchIn(clause.replace(absoluteDate, "")))
            val clauseDates = dateTokens(clause, today).distinct()
            require(clauseDates.size <= 1)
            require(dates.size <= 1 || clauseDates.isNotEmpty())
            val date = clauseDates.singleOrNull() ?: commonDate
            val hasTraining = training.containsMatchIn(clause)
            val hasLearning = learning.containsMatchIn(clause)
            val hasFinance = finance.containsMatchIn(clause) && amount.containsMatchIn(clause)
            require(listOf(hasTraining, hasLearning, hasFinance).count { it } == 1)
            val domain = when { hasTraining -> Domain.TRAINING; hasLearning -> Domain.LEARNING; else -> Domain.FINANCE }
            val isPlan = date.isAfter(today) || Regex("安排|计划|待办|提醒").containsMatchIn(clause)
            if (!isPlan) require(!Regex("打算|准备|想要|希望").containsMatchIn(clause))
            val durations = duration.findAll(clause).toList()
            require(durations.size <= 1)
            val minutes = durations.singleOrNull()?.let { minutes(it) } ?: 0
            require(minutes in 0..1440)
            val amounts = amount.findAll(clause).toList()
            require(amounts.size <= 1)
            val fen = amounts.singleOrNull()?.let { Amounts.parseFen(it.groupValues[1]) } ?: 0
            val title = title(clause)
            require(title.isNotBlank())
            if (isPlan) {
                require(clauseDates.isNotEmpty() || dates.size == 1)
                require(domain != Domain.FINANCE || fen > 0)
                val times = time.findAll(clause).map { it.value }.toList()
                require(times.size <= 1)
                val clockTime = times.singleOrNull().orEmpty()
                if (clockTime.isNotEmpty()) LocalTime.parse(clockTime)
                // Planned spending would lose its amount in the Plan model; ask instead.
                require(domain != Domain.FINANCE)
                plans += Plan(title = title, domain = domain, date = date.toString(), time = clockTime, expectedMinutes = minutes)
            } else {
                require(if (domain == Domain.FINANCE) fen > 0 && minutes == 0 else minutes > 0 && fen == 0L)
                val kind = when {
                    Regex("退款|退了|返还").containsMatchIn(clause) -> MoneyKind.REFUND
                    Regex("转账|转了|转给").containsMatchIn(clause) -> MoneyKind.TRANSFER
                    Regex("工资|收入|到账|收款|收到").containsMatchIn(clause) -> MoneyKind.INCOME
                    else -> MoneyKind.EXPENSE
                }
                records += LifeRecord(title = title, domain = domain, date = date.toString(), minutes = minutes,
                    amountFen = fen, moneyKind = kind, category = if (domain == Domain.FINANCE) "其他" else "",
                    source = "本地整理")
            }
        }
        DraftBatch(records = records, plans = plans)
    }.getOrNull()

    private fun dateTokens(text: String, today: LocalDate): List<LocalDate> {
        val result = absoluteDate.findAll(text).map { LocalDate.parse(it.value).also { date -> require(date.year in 1900..2100) } }.toMutableList()
        Regex("今天|今日|今晚|昨天|昨日|昨晚|前天|明天|明日|明晚|后天").findAll(text).forEach {
            result += when (it.value) {
                "昨天", "昨日", "昨晚" -> today.minusDays(1)
                "前天" -> today.minusDays(2)
                "明天", "明日", "明晚" -> today.plusDays(1)
                "后天" -> today.plusDays(2)
                else -> today
            }
        }
        return result
    }

    private fun minutes(match: MatchResult): Int {
        if (match.value.startsWith("半")) return 30
        val value = match.groupValues[1].toBigDecimalOrNull() ?: BigDecimal(chineseNumber(match.groupValues[1]))
        val result = when (match.groupValues[2]) {
            "半小时" -> value.multiply(BigDecimal(60)).add(BigDecimal(30))
            "小时" -> value.multiply(BigDecimal(60))
            else -> value
        }
        return result.intValueExact().also { require(it > 0) }
    }

    private fun chineseNumber(text: String): Int {
        val digits = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0
        var number = 0
        for (char in text) {
            when (char) {
                '十' -> { total += (if (number == 0) 1 else number) * 10; number = 0 }
                '百' -> { total += (if (number == 0) 1 else number) * 100; number = 0 }
                else -> { require(number == 0); number = digits[char] ?: error("unsupported number") }
            }
        }
        return total + number
    }

    private fun title(clause: String): String = clause.replace(absoluteDate, "").replace(time, "")
        .replace(Regex("今天|今日|今晚|昨天|昨日|昨晚|前天|明天|明日|明晚|后天"), "")
        .replace(duration, "").replace(amount, "")
        .replace(Regex("^(请|帮我|我|记录一下|记录|记一下|记下|安排|计划|提醒我|待办|打算|准备|想要)+"), "")
        .replace(Regex("[：: ]+"), " ").trim().trimEnd('了').trim().take(80)

    private fun summary(text: String, state: AppState, today: LocalDate): AssistantReply {
        if (!state.settings.includeHistory) return AssistantReply("本地摘要未开启，可在模型设置中允许后再查看汇总。")
        val week = Regex("本周|这周|周复盘").containsMatchIn(text)
        val start = if (week) Progress.weekStart(today) else today
        val records = state.records.filter {
            val date = runCatching { LocalDate.parse(it.date) }.getOrNull()
            date != null && !date.isBefore(start) && !date.isAfter(today)
        }
        val trainingMinutes = records.filter { it.domain == Domain.TRAINING }.sumOf { it.minutes }
        val learningMinutes = records.filter { it.domain == Domain.LEARNING }.sumOf { it.minutes }
        val financeText = if (state.settings.includeFinance) "，净支出 ¥${Amounts.yuan(Progress.netExpense(records))}" else ""
        return AssistantReply("本地汇总 · ${if (week) "本周" else "今天"}\n训练 $trainingMinutes 分钟，学习 $learningMinutes 分钟$financeText。")
    }
}
