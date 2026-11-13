package ru.dmitriy.workouts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import ru.dmitriy.workouts.core.timer.RestTimer
import ru.dmitriy.workouts.data.BackupEnvelope
import ru.dmitriy.workouts.data.BackupManager
import ru.dmitriy.workouts.feature.AppViewModel
import ru.dmitriy.workouts.feature.WorkoutsApp
import ru.dmitriy.workouts.feature.shared.ActionButton
import ru.dmitriy.workouts.feature.shared.Panel
import ru.dmitriy.workouts.ui.theme.*
import javax.inject.Inject

@AndroidEntryPoint class MainActivity:ComponentActivity() {
    @Inject lateinit var backup:BackupManager
    @Inject lateinit var timer:RestTimer
    private val viewModel:AppViewModel by viewModels()
    private var includeChats=false
    private var pendingImport by mutableStateOf<BackupEnvelope?>(null)
    private val jsonFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) launchFile { backup.exportJson(uri,includeChats); "Резервная копия сохранена" }
    }
    private val csvFile=registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if(uri!=null) launchFile { backup.exportCsv(uri); "Таблица тренировок сохранена" }
    }
    private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) launchFile { pendingImport=backup.inspect(uri); "Файл проверен" }
    }
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* Timer remains available after refusal. */ }
    private fun launchFile(action:suspend ()->String) { lifecycleScope.launch {
        val message=runCatching { action() }.getOrElse { it.message ?: "Не удалось обработать файл" }
        Toast.makeText(this@MainActivity,message,Toast.LENGTH_LONG).show()
    } }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(AppColors.Transparent.toArgb()),navigationBarStyle=SystemBarStyle.dark(AppColors.Transparent.toArgb()))
        lifecycleScope.launch { timer.state.collect { state ->
            val prefs=getSharedPreferences("permission_prompts",MODE_PRIVATE)
            if(state.running && Build.VERSION.SDK_INT>=33 && !prefs.getBoolean("notifications",false) && ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("notifications",true).apply(); notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } }
        setContent { WorkoutsTheme {
            WorkoutsApp(viewModel,
                onExportJson={ chats -> includeChats=chats;jsonFile.launch("Тренировки-резервная-копия.json") },
                onExportCsv={ csvFile.launch("Тренировки-история.csv") },
                onImport={ importFile.launch(arrayOf("application/json","text/plain","application/octet-stream")) })
            pendingImport?.let { incoming -> Dialog(onDismissRequest={pendingImport=null}) {
                Panel { Text("Заменить записи?",style=AppType.Heading,color=AppColors.Text)
                    Text("В файле: ${incoming.data.sessions.size} занятий, ${incoming.data.messages.size} сообщений. Программа, журнал и диалоги будут заменены. Ключ DeepSeek не изменится. Сначала сохраните копию, если текущие записи нужны.",style=AppType.Body,color=AppColors.Secondary)
                    ActionButton("Заменить данные",{ pendingImport=null;timer.skip();launchFile { backup.restore(incoming);"Данные восстановлены" } },Modifier.fillMaxWidth(),primary=true)
                    ActionButton("Отмена",{pendingImport=null},Modifier.fillMaxWidth())
                }
            } }
        } }
    }
}
