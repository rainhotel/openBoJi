package com.boji.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boji.app.model.AppActions
import com.boji.app.ui.BojiApp
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val model: BojiViewModel by viewModels()

    override fun onResume() { super.onResume(); model.refresh() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);intent.getStringExtra("taskId")?.let(model::openTask) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent.getStringExtra("taskId")?.let(model::openTask)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            var exportPassword by remember { mutableStateOf("") }
            var importPassword by remember { mutableStateOf("") }
            val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(model::attachImage) }
            var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
            val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                if(success)cameraUri?.let { model.attachImage(android.net.Uri.parse(it)) }
            }
            val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
            val backupWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                uri?.let { model.writeBackup(it,exportPassword) }; exportPassword=""
            }
            val backupReader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { model.readBackup(it,importPassword) }; importPassword=""
            }
            val csvWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(model::writeCsv) }
            val skillReader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::receiveSkill) }
            val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(model::receiveVoice)
                }
            }
            val actions = remember {
                object : AppActions by model {
                    override fun selectImage() { imagePicker.launch("image/*") }
                    override fun takePhoto() {
                        val folder=java.io.File(cacheDir,"camera").apply { mkdirs() }
                        val file=java.io.File(folder,"${java.util.UUID.randomUUID()}.jpg")
                        val uri=androidx.core.content.FileProvider.getUriForFile(this@MainActivity,"$packageName.files",file)
                        cameraUri=uri.toString()
                        try { camera.launch(uri) } catch(_: Exception) { model.showError("设备暂无相机服务，请从相册选取") }
                    }
                    override fun requestNotifications() {
                        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else {
                            try { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName)) }
                            catch(_: Exception) { model.showError("请在系统设置开启薄肌通知") }
                        }
                    }
                    override fun requestPreciseReminders() {
                        if(android.os.Build.VERSION.SDK_INT>=31)try {
                            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,android.net.Uri.parse("package:$packageName")))
                        } catch(_: Exception) { model.showError("请在系统设置允许闹钟和提醒") }
                    }
                    override fun importSkill() { skillReader.launch(arrayOf("text/*","application/octet-stream")) }
                    override fun voiceInput() {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN")
                            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT,"说点什么")
                        try { speech.launch(intent) }
                        catch (_: Exception) { model.showError("设备暂无语音服务，请用键盘输入") }
                    }
                    override fun exportBackup(password: String) {
                        if (password.length < 8) { model.showError("备份密码至少 8 位"); return }
                        exportPassword=password; backupWriter.launch("薄肌-${LocalDate.now()}.boji")
                    }
                    override fun importBackup(password: String) {
                        if (password.length < 8) { model.showError("请输入至少 8 位备份密码"); return }
                        importPassword=password; backupReader.launch(arrayOf("*/*"))
                    }
                    override fun exportCsv() { csvWriter.launch("薄肌记录-${LocalDate.now()}.csv") }
                }
            }
            BojiApp(state,actions)
        }
    }
}
