package com.boji.app.data

import com.boji.app.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Recurrence metadata is committed and backed up with the same database transaction. */
internal object RecurrenceJson {
    const val EMPTY = "{\"rules\":[],\"exceptions\":[]}"

    fun validate(json: String, goals: List<WeeklyGoal>): JSONObject {
        val root = JSONObject(json)
        val rules = root.getJSONArray("rules")
        val exceptions = root.getJSONArray("exceptions")
        require(rules.length() <= 1000 && exceptions.length() <= 100_000) { "重复安排过多" }
        val ids = mutableSetOf<String>()
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            val id = rule.getString("id")
            require(ids.add(id)) { "重复规则标识冲突" }
            val minutes = rule.get("minutes")
            require(minutes is Number && minutes.toString().matches(Regex("0|[1-9][0-9]*"))) { "重复时长无效" }
            val plan = Plan(id=id,title=rule.getString("title"),domain=Domain.valueOf(rule.getString("domain")),
                date=rule.getString("date"),time=rule.getString("time"),expectedMinutes=minutes.toString().toInt(),
                goalId=rule.optString("goalId").takeIf { it.isNotBlank() && it != "null" },seriesId=id)
            DataValidation.plan(plan)
            require(plan.goalId == null || goals.any { it.id == plan.goalId && it.domain == plan.domain }) { "重复规则关联目标不存在" }
            val days = rule.getJSONArray("days")
            require(days.length() in 1..7) { "重复规则请选择星期" }
            val weekdays = (0 until days.length()).map {
                val day = days.get(it)
                require(day is Number && day.toString().matches(Regex("[1-7]"))) { "重复星期无效" }
                day.toString().toInt()
            }
            require(weekdays.distinct().size == weekdays.size) { "重复星期不能重复" }
        }
        val skipped = (0 until exceptions.length()).map {
            val value = exceptions.get(it)
            require(value is String) { "重复例外格式无效" }
            DataValidation.id(value)
            value
        }
        DataValidation.unique(skipped,"重复例外")
        return root
    }

    fun merge(old: String, incoming: String, goals: List<WeeklyGoal>): String {
        val first = validate(old,goals)
        val second = validate(incoming,goals)
        val rules = linkedMapOf<String,JSONObject>()
        val exceptions = linkedSetOf<String>()
        for (root in listOf(first,second)) {
            val array = root.getJSONArray("rules")
            for (i in 0 until array.length()) rules[array.getJSONObject(i).getString("id")]=array.getJSONObject(i)
            val skip = root.getJSONArray("exceptions")
            for (i in 0 until skip.length()) exceptions.add(skip.getString(i))
        }
        return JSONObject().put("rules",JSONArray(rules.values.toList())).put("exceptions",JSONArray(exceptions.toList())).toString()
            .also { validate(it,goals) }
    }
}
