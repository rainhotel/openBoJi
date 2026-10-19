package com.boji.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boji.app.backup.BackupCrypto
import com.boji.app.data.BojiStore
import com.boji.app.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupFlowTest {
    @Test fun encryptedFileRestoresReferencedMediaAndRejectsWrongPassword() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val names=listOf("backup-source-${UUID.randomUUID()}.db","backup-target-${UUID.randomUUID()}.db")
        val source=BojiStore(app,names[0]); val target=BojiStore(app,names[1])
        val media=File(app.filesDir,"attachments/${UUID.randomUUID()}.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1,2,3,4)) }
        val file=File(app.cacheDir,"${UUID.randomUUID()}.boji")
        val owners=ViewModelStore()
        try {
            val row=LifeRecord(title="图片晚饭",domain=Domain.FINANCE,amountFen=2800,source="图片",sourceId="image:${media.name}")
            source.saveRecord(row)
            source.saveSettings(ModelSettings(connections=listOf(ModelConnection(apiKey="SECRET-DO-NOT-EXPORT"))))
            val vm=BojiViewModel(app,source).also { owners.put("source",it) }
            await { !vm.state.value.loading }
            vm.writeBackup(Uri.fromFile(file),"password123")
            await { vm.state.value.notice == "已导出加密备份" || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            assertFalse(BackupCrypto.decrypt(file.readBytes(),"password123").toString(Charsets.UTF_8).contains("SECRET-DO-NOT-EXPORT"))
            assertTrue(media.delete())
            val restore=BojiViewModel(app,target).also { owners.put("target",it) }
            await { !restore.state.value.loading }
            restore.readBackup(Uri.fromFile(file),"wrong-password")
            await { restore.state.value.error != null }
            assertTrue(target.snapshot().records.isEmpty())
            restore.readBackup(Uri.fromFile(file),"password123")
            await { restore.state.value.notice == "已合并恢复" }
            assertEquals(listOf(row),target.snapshot().records)
            assertArrayEquals(byteArrayOf(1,2,3,4),media.readBytes())
            assertEquals("",target.snapshot().settings.connections[0].apiKey)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owners.clear() }
            source.close(); target.close(); names.forEach(app::deleteDatabase); media.delete(); file.delete()
        }
    }

    private fun await(test: () -> Boolean) {
        val end=SystemClock.uptimeMillis()+15_000
        while (!test() && SystemClock.uptimeMillis() < end) Thread.sleep(25)
        assertTrue("Timed out waiting for ViewModel operation",test())
    }
}
