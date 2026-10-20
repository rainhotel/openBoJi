package com.boji.app.reminder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import com.boji.app.MainActivity
import com.boji.app.data.BojiStore
import com.boji.app.model.*
import org.json.JSONArray
import java.time.*

/** Local alarms, exact when permitted, with an inexact fallback. Android/OEM battery policy still applies. */
object TaskReminders {
    const val CHANNEL="boji-tasks"
    fun precise(context: Context)=Build.VERSION.SDK_INT<31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun allowed(context: Context): Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun triggerAt(plan: Plan, zone: ZoneId=ZoneId.systemDefault()): Long? = plan.reminderMinutes?.let {
        if(plan.time.isBlank() || plan.status !in setOf(PlanStatus.TODO,PlanStatus.IN_PROGRESS)) null
        else LocalDateTime.of(LocalDate.parse(plan.date),LocalTime.parse(plan.time)).atZone(zone).minusMinutes(it.toLong()).toInstant().toEpochMilli()
    }
    private fun pending(context: Context,id: String): PendingIntent = PendingIntent.getBroadcast(context,0,
        Intent(context,TaskReminderReceiver::class.java).setData(android.net.Uri.parse("boji://reminder/$id")).putExtra("id",id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @Synchronized fun rebuild(context: Context, plans: List<Plan>) {
        val prefs=context.getSharedPreferences("reminders",Context.MODE_PRIVATE)
        val manager=context.getSystemService(AlarmManager::class.java)
        val previous=runCatching { JSONArray(prefs.getString("ids","[]")) }.getOrDefault(JSONArray())
        for(i in 0 until previous.length()) manager.cancel(pending(context,previous.getString(i)))
        val now=System.currentTimeMillis(); val ids=JSONArray()
        if(allowed(context)) plans.forEach { plan -> triggerAt(plan)?.takeIf { it>now }?.let { at ->
            if(precise(context))manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(context,plan.id))
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(context,plan.id))
            ids.put(plan.id)
        } }
        prefs.edit().putString("ids",ids.toString()).apply()
    }
    fun notify(context: Context,id: String) {
        if(!allowed(context)) return
        val store=BojiStore(context)
        val plan=try { store.snapshot().plans.find { it.id==id } } finally { store.close() } ?: return
        if(plan.status !in setOf(PlanStatus.TODO,PlanStatus.IN_PROGRESS)) return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"日程提醒",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,MainActivity::class.java).putExtra("taskId",id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open=PendingIntent.getActivity(context,id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(context,CHANNEL).setSmallIcon(com.boji.app.R.drawable.ic_launcher)
            .setContentTitle(plan.title).setContentText("${plan.date} ${plan.time} · ${plan.domain.label} · P${plan.priority}")
            .setContentIntent(open).setAutoCancel(true).build()
        manager.notify(id.hashCode(),notification)
    }
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        Thread {
            try {
                if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED,"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) {
                    val store=BojiStore(context)
                    try { TaskReminders.rebuild(context,store.snapshot().plans) } finally { store.close() }
                } else intent.getStringExtra("id")?.let { TaskReminders.notify(context,it) }
            } catch(_: Exception) { android.util.Log.w("BojiReminder","Reminder could not be delivered") }
            finally { pending.finish() }
        }.start()
    }
}
