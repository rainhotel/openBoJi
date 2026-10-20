package com.boji.app.model
import com.boji.app.reminder.TaskReminders
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class ReminderTimeTest {
    @Test fun localTimeAndLeadMinutesProduceAnExplicitInstant() {
        val task=Plan(date="2026-11-01",time="23:00",reminderMinutes=15)
        assertEquals(Instant.parse("2026-11-01T14:45:00Z").toEpochMilli(),TaskReminders.triggerAt(task,ZoneId.of("Asia/Shanghai")))
        assertNull(TaskReminders.triggerAt(task.copy(status=PlanStatus.ARCHIVED)))
        assertNull(TaskReminders.triggerAt(task.copy(time="")))
        assertNull(TaskReminders.triggerAt(task.copy(reminderMinutes=null)))
    }
}
