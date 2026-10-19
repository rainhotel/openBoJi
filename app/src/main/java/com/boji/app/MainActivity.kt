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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boji.app.model.AppActions
import com.boji.app.ui.BojiApp
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val model: BojiViewModel by viewModels()

    override fun onResume() { super.onResume(); model.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            var exportPassword by remember { mutableStateOf("") }
            var importPassword by remember { mutableStateOf("") }
            val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(model::attachImage) }
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
