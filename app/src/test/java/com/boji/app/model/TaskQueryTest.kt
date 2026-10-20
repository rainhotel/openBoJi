package com.boji.app.model

import org.junit.Assert.*
import org.junit.Test

class TaskQueryTest {
    @Test fun multiSortKeepsNullDeadlinesLastAndUsesStableIds() {
        val plans=listOf(Plan(id="z",title="空截止",priority=0),Plan(id="c",title="晚",deadline="2026-11-05",priority=0),
            Plan(id="b",title="早低",deadline="2026-11-03",priority=2),Plan(id="a",title="早高",deadline="2026-11-03",priority=0))
        val view=SavedView("考试",sorts=listOf("deadline","priority"))
        assertEquals(listOf("a","b","c","z"),TaskQuery.select(plans,view).map { it.id })
        assertEquals(listOf("c","a","b","z"),TaskQuery.select(plans,view.copy(sorts=listOf("-deadline","priority"))).map { it.id })
        val ties=listOf(Plan(id="b"),Plan(id="a"))
        assertEquals(listOf("a","b"),TaskQuery.select(ties,view).map { it.id })
        val mixed=listOf(Plan(id="whole-day",deadline="2026-11-03"),Plan(id="morning",deadline="2026-11-03T09:00"))
        assertEquals(listOf("morning","whole-day"),TaskQuery.select(mixed,view).map { it.id })
    }
    @Test fun filteringAndGroupingDoNotChangeTheUnderlyingTasks() {
        val tasks=listOf(Plan(id="training",domain=Domain.TRAINING,status=PlanStatus.IN_PROGRESS),Plan(id="archived",status=PlanStatus.ARCHIVED))
        assertEquals(listOf("training"),TaskQuery.select(tasks,SavedView("全部")).map { it.id })
        assertEquals("训练",TaskQuery.group(tasks.first(),"domain"))
        assertEquals(listOf("archived"),TaskQuery.select(tasks,SavedView("归档",status="ARCHIVED")).map { it.id })
        assertEquals(PlanStatus.IN_PROGRESS,tasks.first().status)
    }
    @Test fun scheduleConflictReportsOverlapAndKeepsOriginalPlans() {
        val old=Plan(title="原有安排",date="2026-11-01",time="19:00",expectedMinutes=60)
        val incoming=Plan(title="复习",date=old.date,time="19:30",expectedMinutes=30)
        assertEquals(1,TaskQuery.conflicts(listOf(incoming),listOf(old)).size)
        assertTrue(TaskQuery.conflicts(listOf(incoming.copy(time="20:00")),listOf(old)).isEmpty())
        assertEquals("19:00",old.time)
    }
}
