package com.boji.app.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Both schedule views consume the same stable task IDs. View options never mutate facts. */
object TaskQuery {
    private fun deadline(value:String)=if(value.contains('T'))LocalDateTime.parse(value) else LocalDate.parse(value).atTime(LocalTime.MAX)
    fun select(plans: List<Plan>, view: SavedView, query: String = "", today: LocalDate = LocalDate.now()): List<Plan> {
        val start=Progress.weekStart(today).toString(); val end=Progress.weekStart(today).plusDays(7).toString()
        val rows=plans.filter { (view.domain == null || it.domain == view.domain) &&
            (!view.thisWeek || it.date >= start && it.date < end) && (if(view.status.isBlank())it.status!=PlanStatus.ARCHIVED else it.status.name == view.status) &&
            (query.isBlank() || (it.title+" "+it.project).contains(query,true)) }
        return rows.sortedWith { a,b ->
            var value=0
            for(key in view.sorts) {
                val desc=key.startsWith("-")
                value=when(key.removePrefix("-")) {
                    "deadline" -> when { a.deadline.isBlank() && b.deadline.isNotBlank() -> 1; b.deadline.isBlank() && a.deadline.isNotBlank() -> -1
                        a.deadline.isBlank() -> 0
                        else -> deadline(a.deadline).compareTo(deadline(b.deadline))*(if(desc)-1 else 1) }
                    "priority" -> a.priority.compareTo(b.priority)*(if(desc)-1 else 1)
                    "domain" -> a.domain.ordinal.compareTo(b.domain.ordinal)*(if(desc)-1 else 1)
                    "status" -> a.status.ordinal.compareTo(b.status.ordinal)*(if(desc)-1 else 1)
                    "date" -> (a.date+"T"+a.time).compareTo(b.date+"T"+b.time)*(if(desc)-1 else 1)
                    else -> 0
                }
                if(value != 0) break
            }
            if(value == 0) a.id.compareTo(b.id) else value
        }
    }
    fun group(plan: Plan, key: String): String = when(key) {
        "domain" -> plan.domain.label; "status" -> plan.status.label; "priority" -> "P${plan.priority}"; "project" -> plan.project.ifBlank { "未分项目" }; else -> "全部任务"
    }
    fun conflicts(incoming: List<Plan>, existing: List<Plan>): List<String> = incoming.flatMap { plan ->
        if(plan.time.isBlank()) emptyList() else {
            val start=LocalDateTime.of(LocalDate.parse(plan.date),LocalTime.parse(plan.time))
            val end=start.plusMinutes(plan.expectedMinutes.coerceAtLeast(1).toLong())
            existing.filter { it.status !in setOf(PlanStatus.DONE,PlanStatus.SKIPPED,PlanStatus.ARCHIVED) && it.time.isNotBlank() }.mapNotNull { old ->
                val oldStart=LocalDateTime.of(LocalDate.parse(old.date),LocalTime.parse(old.time))
                val oldEnd=oldStart.plusMinutes(old.expectedMinutes.coerceAtLeast(1).toLong())
                if(start < oldEnd && oldStart < end) "${plan.date} ${plan.time} ${plan.title} 与 ${old.title} 时间重叠" else null
            }
        }
    }.distinct()
}
