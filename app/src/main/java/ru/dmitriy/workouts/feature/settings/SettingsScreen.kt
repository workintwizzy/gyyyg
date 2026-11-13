package ru.dmitriy.workouts.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import ru.dmitriy.workouts.data.AppSettings
import ru.dmitriy.workouts.domain.Profile
import ru.dmitriy.workouts.feature.shared.*
import ru.dmitriy.workouts.feature.workout.grams
import ru.dmitriy.workouts.ui.theme.*

@Composable fun SettingsScreen(profile: Profile, settings: AppSettings, connected: Boolean, onProfile: () -> Unit, onSettings: (AppSettings) -> Unit,
    onConnect: (String, String) -> Unit, onDisconnect: () -> Unit, onClearChats: () -> Unit, onJson: (Boolean) -> Unit, onCsv: () -> Unit, onImport: () -> Unit) {
    // A secret is deliberately kept out of saved instance state.
    var key by remember { mutableStateOf("") }
    var editKey by remember(connected) { mutableStateOf(!connected) }
    var dataDetails by rememberSaveable { mutableStateOf(false) }
    val uri=LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.Large).imePadding(), verticalArrangement = Arrangement.spacedBy(Space.Section)) {
        Text("Настройки", style = AppType.Hero, color = AppColors.Text)
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Профиль")
            Text("${profile.age} лет · ${profile.heightCm} см · ${profile.goal}", style = AppType.Body, color = AppColors.Secondary)
            ActionButton("Изменить профиль", onProfile, Modifier.fillMaxWidth())
        }
        Panel(Modifier.fillMaxWidth(), outlined = true) {
            SectionLabel("ИИ-помощник",if(connected)"Ключ сохранён" else "Не подключён")
            Text("Личный ключ DeepSeek хранится на этом телефоне. Для новых ответов нужен интернет.", style = AppType.Body, color = AppColors.Secondary)
            ActionButton(if(dataDetails)"Скрыть подробности" else "Что увидит помощник",{dataDetails=!dataDetails},Modifier.fillMaxWidth())
            if(dataDetails)Text("Только после вашего вопроса в DeepSeek отправятся цель, возраст и рост, нужные упражнения, выбранные записи и несколько последних реплик. Заметки — только с отдельного разрешения. Ответы останутся на телефоне. Запросы оплачиваются с вашего счёта DeepSeek.",style=AppType.Body,color=AppColors.Secondary)
            SettingToggle("Разрешить отправку выбранных записей в DeepSeek", settings.aiConsent) { onSettings(settings.copy(aiConsent = it)) }
            SettingToggle("Добавлять мои заметки", settings.includeNotes) { onSettings(settings.copy(includeNotes = it)) }
            if(editKey) {
                SecretKeyInput(key,{key=it.take(256)})
                ActionButton("Сохранить ключ", { onConnect("", key); key = "" }, Modifier.fillMaxWidth(), primary = true, enabled = settings.aiConsent && key.trim().length in 16..256)
                ActionButton("Получить ключ DeepSeek",{uri.openUri("https://platform.deepseek.com/api_keys")},Modifier.fillMaxWidth())
            } else {
                Text("Проверим ключ при первом вопросе.",style=AppType.Label,color=AppColors.Secondary)
                ActionButton("Заменить ключ",{editKey=true},Modifier.fillMaxWidth())
            }
            if (connected) ActionButton("Удалить ключ с телефона", onDisconnect, Modifier.fillMaxWidth())
        }
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Отдых")
            SettingToggle("Звук сигнала", settings.sound) { onSettings(settings.copy(sound = it)) }
            SettingToggle("Вибрация", settings.vibration) { onSettings(settings.copy(vibration = it)) }
            Text("При закрытом приложении сигнал может прийти позже.", style = AppType.Label, color = AppColors.Secondary)
        }
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Данные")
            SettingToggle("Включать диалоги в резервную копию", settings.includeChatsInBackup) { onSettings(settings.copy(includeChatsInBackup = it)) }
            ActionButton("Сохранить копию", { onJson(settings.includeChatsInBackup) }, Modifier.fillMaxWidth())
            ActionButton("Сохранить таблицу тренировок", onCsv, Modifier.fillMaxWidth())
            ActionButton("Восстановить из копии", onImport, Modifier.fillMaxWidth())
            Text("Ключ DeepSeek не попадёт в копию.", style = AppType.Label, color = AppColors.Secondary)
            ActionButton("Очистить историю тренера", onClearChats, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Space.Section))
    }
}

@Composable private fun SecretKeyInput(value:String,onChange:(String)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(Space.Small)) {
        Text("Личный ключ DeepSeek",style=AppType.Label,color=AppColors.Secondary)
        BasicTextField(value,onChange,Modifier.fillMaxWidth().semantics { contentDescription="Личный ключ DeepSeek" }
            .heightIn(min=Space.Action).clip(AppShapes.Control).background(AppColors.Raised)
            .border(Space.Hairline,AppColors.Line,AppShapes.Control).padding(Space.Regular),singleLine=true,
            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
            textStyle=AppType.Body.copy(color=AppColors.Text),cursorBrush=SolidColor(AppColors.Accent))
    }
}

@Composable fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        Text(label, style = AppType.Body, color = AppColors.Text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.Small), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            Chip("Включено", checked) { onChange(true) }
            Chip("Выключено", !checked) { onChange(false) }
        }
    }
}

@Composable fun ProfileScreen(profile: Profile, firstRun: Boolean, onBodyWeight: (Int) -> Unit = {}, onSave: (Profile) -> Unit) {
    var age by rememberSaveable { mutableStateOf(profile.age.toString()) }
    var height by rememberSaveable { mutableStateOf(profile.heightCm.toString()) }
    var goal by rememberSaveable { mutableStateOf(profile.goal) }
    var preferences by rememberSaveable { mutableStateOf(profile.preferences) }
    var weight by rememberSaveable { mutableStateOf("65") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.Large).imePadding(), verticalArrangement = Arrangement.spacedBy(Space.Section)) {
        Text(if (firstRun) "Начнём\nс основы" else "Ваш профиль", style = AppType.Hero, color = AppColors.Text)
        Text("Программа для первых недель в зале. Рабочий вес вы подберёте на месте по технике и усилию.", style = AppType.Body, color = AppColors.Secondary)
        Input(age, { age = it }, "Возраст", numeric = true)
        Input(height, { height = it }, "Рост, см", numeric = true)
        if (firstRun) Input(weight, { weight = it }, "Масса тела, кг · необязательно", numeric = true)
        Input(goal, { goal = it }, "Цель")
        Input(preferences, { preferences = it }, "Предпочтения, необязательно", multiline = true)
        Text("Массу тела можно вести в прогрессе. Подключение помощника — отдельно в настройках.", style = AppType.Label, color = AppColors.Secondary)
        ActionButton(if (firstRun) "Начать вести журнал" else "Сохранить профиль", {
            if (firstRun) grams(weight)?.let(onBodyWeight)
            onSave(Profile(age.toIntOrNull() ?: profile.age, height.toIntOrNull() ?: profile.heightCm, goal.trim(), preferences.trim(), true))
        }, Modifier.fillMaxWidth(), primary = true, enabled = age.toIntOrNull() in 16..100 && height.toIntOrNull() in 100..250 && goal.isNotBlank() && (!firstRun || weight.isBlank() || (grams(weight) ?: 0) in 20_000..400_000))
    }
}

