package com.boji.app

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boji.app.data.BojiStore
import com.boji.app.model.Plan
import com.boji.app.reminder.TaskReminders
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Dedicated emulator only: wait for an actual AlarmManager broadcast and visible app notification. */
@RunWith(AndroidJUnit4::class)
class NotificationDeliveryTest {
    @Test fun scheduledAlarmProducesARealNotification() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        if(android.os.Build.VERSION.SDK_INT>=33) instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").let {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input->input.readBytes() }
        }
        assertTrue("Notification permission must be available in the dedicated emulator",TaskReminders.allowed(context))
        if(android.os.Build.VERSION.SDK_INT>=31) instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow").let {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input->input.readBytes() }
        }
        val now=LocalDateTime.now();var at=now.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES)
        if(ChronoUnit.SECONDS.between(now,at)<10)at=at.plusMinutes(1)
        val task=Plan(title="自动验收提醒",date=at.toLocalDate().toString(),time=at.toLocalTime().toString(),reminderMinutes=0)
        val store=BojiStore(context);val manager=context.getSystemService(NotificationManager::class.java)
        try {
            store.savePlan(task);TaskReminders.rebuild(context,store.snapshot().plans)
            val timeout=android.os.SystemClock.elapsedRealtime()+110000
            while(android.os.SystemClock.elapsedRealtime()<timeout && manager.activeNotifications.none { it.id==task.id.hashCode() }) android.os.SystemClock.sleep(250)
            assertTrue("Scheduled reminder did not produce a notification",manager.activeNotifications.any { it.id==task.id.hashCode() })
        } finally {
            manager.cancel(task.id.hashCode());store.deletePlan(task.id);TaskReminders.rebuild(context,store.snapshot().plans);store.close()
        }
    }
}
