package ru.dmitriy.workouts.feature.coach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import ru.dmitriy.workouts.domain.*
import ru.dmitriy.workouts.feature.AppDialog
import ru.dmitriy.workouts.feature.plan.operationLabel
import ru.dmitriy.workouts.feature.plan.operationValue
import ru.dmitriy.workouts.feature.plan.proposalStatus
import ru.dmitriy.workouts.feature.shared.*
import ru.dmitriy.workouts.ui.theme.*

/** Native conversation and reviewed changes. Model-generated code is never executed. */
@Composable fun CoachScreen(
    state: LocalState, connected: Boolean, phase: String, response: String, error: String?, contextLabel: String,
    onSend: (String) -> Unit, onCancel: () -> Unit, onConnect: () -> Unit, onConversation: (String) -> Unit,
    selectedConversationId: String?, onNew: () -> Unit, onProposal: (String) -> Unit, onInsight: (String) -> Unit,
    initialDraft: String = "", persistedDraft: String? = null, onDraft: (String) -> Unit = {},
    onRequestProposal: ((String) -> Unit)? = null,
    onApplyProposal: ((String) -> Unit)? = null, onRejectProposal: ((String) -> Unit)? = null,
    targetSessionId: String? = null,
    onBack: (() -> Unit)? = null,
    requestBusy: Boolean = false,
    streamConversationId: String? = null,
    streamMessageId: String? = null,
    uiMemory: CoachUiMemory = rememberCoachUiMemory(),
    onDeleteConversation: (String) -> Unit = {},
) {
    val threadKey = selectedConversationId ?: "draft:${targetSessionId.orEmpty()}:$contextLabel"
    var localDraft by rememberSaveable(threadKey, initialDraft) { mutableStateOf(initialDraft) }
    val draft = persistedDraft ?: localDraft
    fun setDraft(value: String) { localDraft = value; onDraft(value) }
    var history by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val thread = remember(uiMemory, threadKey) { uiMemory.thread(threadKey) }
    val list = thread.list
    val scrollScope = rememberCoroutineScope()
    val scrollInput = remember(thread) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) thread.stopFollowing()
                return Offset.Zero
            }
        }
    }
    DisposableEffect(thread) { onDispose { thread.capturePosition() } }
    val messages = remember(state.messages, selectedConversationId) {
        state.messages.filter {
            it.conversationId == selectedConversationId && it.state != "draft" && it.role in listOf("user", "assistant")
        }.sortedBy { it.createdAt }
    }
    val proposalsById = remember(state.proposals) { state.proposals.associateBy { it.proposalId } }
    val proposalStatuses = remember(messages, proposalsById) {
        messages.mapNotNull { message -> message.proposalId?.let { proposalsById[it]?.status } }
    }
    val statusBelongsHere = streamConversationId != null && streamConversationId == selectedConversationId
    val requestLocked = requestBusy || phase in listOf("sending", "receiving", "streaming", "connecting")
    val sending = statusBelongsHere && requestLocked
    // Room's observer may deliver the committed record one frame after the terminal status.
    // Retain the same bubble until that exact saved message arrives, including partial/cancelled answers.
    val showStream = statusBelongsHere && (streamMessageId == null || messages.none { it.id == streamMessageId }) &&
        (sending || (streamMessageId != null && response.isNotBlank() && phase in listOf("done", "error", "cancelled")))
    val visibleError = error?.takeIf { statusBelongsHere && phase != "cancelled" }
    val revision by rememberUpdatedState(listOf(
        messages.lastOrNull()?.id, messages.lastOrNull()?.content?.length, messages.size,
        if (showStream) response.length else null, showStream, visibleError, phase.takeIf { statusBelongsHere },
        proposalStatuses,
    ))
    // Frame batching uses Compose's clock. Wall-clock delays can outlive an idle frame and swallow
    // a user's jump when the screen is removed or a new response fragment arrives immediately.
    LaunchedEffect(list) {
        snapshotFlow {
            val layout = list.layoutInfo
            listOf(thread.followTail, list.isScrollInProgress, list.canScrollForward, revision,
                list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, layout.totalItemsCount,
                layout.viewportEndOffset, layout.visibleItemsInfo.lastOrNull()?.let { it.index to it.offset + it.size })
        }.conflate().collect {
            withFrameNanos { }
            val layout = list.layoutInfo
            if (layout.totalItemsCount > 0 && layout.visibleItemsInfo.isNotEmpty()) {
                thread.capturePosition()
                if (thread.followTail && !list.isScrollInProgress && list.canScrollForward) {
                    var reachedEnd = false
                    // A user's competing scroll may cancel this child without stopping the observer.
                    coroutineScope {
                        launch {
                            // A short final item aligns the bottom even for an answer taller than the viewport.
                            list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
                            reachedEnd = true
                        }.join()
                    }
                    if (reachedEnd) thread.acknowledgeAutomaticPosition() else thread.stopFollowing()
                }
            }
        }
    }
    fun send() {
        if (!connected || draft.isBlank() || requestLocked) return
        thread.followLatest()
        focus.clearFocus()
        if (thread.changeMode && onRequestProposal != null) onRequestProposal(draft) else onSend(draft)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxHeight < Space.CompactWorkout || LocalDensity.current.fontScale > 1.4f
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.Large), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconAction(Icons.Default.ArrowBack, "Назад к занятию", onBack)
            Column(Modifier.weight(1f)) {
                Text("ИИ-тренер", style = AppType.Title, color = AppColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (compact) Text(contextLabel.removePrefix("Контекст: "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = AppType.Caption, color = AppColors.Secondary)
            }
            IconAction(Icons.Default.History, "Диалоги и разборы", { focus.clearFocus(); history = true })
            IconAction(Icons.Default.Settings, "Настройки помощника", onConnect)
        }
        if (!compact) Row(Modifier.fillMaxWidth().padding(horizontal = Space.Large), verticalAlignment = Alignment.CenterVertically) {
            Text(contextLabel.removePrefix("Контекст: "), style = AppType.Label, color = AppColors.Secondary, modifier = Modifier.weight(1f))
        }
        if (onRequestProposal != null) Row(Modifier.fillMaxWidth().padding(horizontal = Space.Large)) {
            listOf("Вопрос", "Изменение").forEachIndexed { index, label ->
                val selected = thread.changeMode == (index == 1)
                Column(Modifier.weight(1f).selectable(selected = selected, role = Role.Tab,
                    onClick = { thread.changeMode = index == 1 }), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().heightIn(min = Space.Touch).padding(vertical = Space.Medium), contentAlignment = Alignment.Center) {
                        Text(label, style = AppType.Label, color = if (selected) AppColors.Accent else AppColors.Secondary)
                    }
                    Box(Modifier.fillMaxWidth().height(Space.Stroke).background(if (selected) AppColors.Accent else AppColors.Line))
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("coach-messages").nestedScroll(scrollInput)
            .semantics {
                // These are the same accessibility actions as LazyColumn's defaults. Claiming the
                // position before launching motion also covers instant TalkBack/keyboard jumps.
                scrollToIndex { index ->
                    if (index !in 0 until list.layoutInfo.totalItemsCount) false else {
                        thread.stopFollowing()
                        scrollScope.launch { list.scrollToItem(index) }
                        true
                    }
                }
                scrollBy { _, y ->
                    thread.stopFollowing()
                    scrollScope.launch { list.scrollBy(y) }
                    true
                }
            }, state = list,
            contentPadding = PaddingValues(horizontal = Space.Large, vertical = Space.Small),
            verticalArrangement = Arrangement.spacedBy(Space.Regular)) {
            items(messages, key = { "message:${it.id}" }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                    if (message.role != "user") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
                        Icon(Icons.Default.AutoAwesome, null, tint = AppColors.Accent, modifier = Modifier.size(Space.Icon))
                        Text("ИИ-помощник", style = AppType.Label, color = AppColors.Accent)
                    }
                    if (message.role == "user") Panel { Text(message.content, style = AppType.Body, color = AppColors.Text) }
                    else Text(message.content.ifBlank { "Ответ не получен" }, style = AppType.Body, color = AppColors.Text)
                    if (message.state != "saved") Text(messageState(message.state), style = AppType.Label, color = AppColors.Secondary)
                    // Sharing a workout is not a conversation link: only the answer that produced a proposal owns its card.
                    val proposal = message.proposalId?.let { proposalsById[it] }
                    if (message.role == "assistant" && proposal != null) CoachProposalCard(proposal, state.exercises,
                        { onProposal(proposal.proposalId) },
                        onApplyProposal?.let { action -> { action(proposal.proposalId) } },
                        onRejectProposal?.let { action -> { action(proposal.proposalId) } })
                }
            }
            if (showStream) item(key = streamMessageId?.let { "message:$it" } ?: "pending") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
                    Text("ИИ-помощник", style = AppType.Label, color = AppColors.Accent)
                    Text(response.ifBlank { "Готовлю ответ…" }, style = AppType.Body, color = AppColors.Text)
                    if (sending) ActionButton("Остановить", onCancel)
                }
            }
            if (visibleError != null) item(key = "error") {
                Panel(Modifier.fillMaxWidth(), outlined = true) {
                    Text("Ответ недоступен", style = AppType.Heading, color = AppColors.Error)
                    Text(visibleError, style = AppType.Body, color = AppColors.Secondary)
                    if (connected && messages.lastOrNull { it.role == "user" } != null) ActionButton("Повторить вопрос", {
                        setDraft(messages.last { it.role == "user" }.content)
                    })
                }
            }
            if (statusBelongsHere && phase == "cancelled") item(key = "cancelled") { Text("Ответ остановлен", style = AppType.Label, color = AppColors.Secondary) }
            if (!connected) item(key = "connection") {
                Panel(Modifier.fillMaxWidth(), outlined = true) {
                    Text("Подключите ИИ-тренера", style = AppType.Heading, color = AppColors.Text)
                    Text("Для новых ответов нужен интернет. Сохранённое доступно всегда.", style = AppType.Body, color = AppColors.Secondary)
                    ActionButton("Подключить", onConnect, Modifier.fillMaxWidth(), primary = true)
                }
            }
            item(key = "tail") { Spacer(Modifier.height(Space.Tiny)) }
        }
        DividerLine()
        Column(Modifier.fillMaxWidth().background(AppColors.Background).padding(horizontal = Space.Large, vertical = Space.Small),
            verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
                CoachDraftInput(draft, ::setDraft, if (connected) "Ваш вопрос" else "Черновик", compact, Modifier.weight(1f))
                ActionButton("", ::send, Modifier.semantics { contentDescription = if (thread.changeMode) "Предложить изменение" else "Отправить" }, primary = true,
                    enabled = connected && draft.isNotBlank() && !requestLocked, icon = Icons.AutoMirrored.Filled.Send)
            }
        }
    }
    }
    if (history) CoachHistoryDialog(state, { history = false }, { history = false; onNew() },
        { id -> history = false; onConversation(id) }, { id -> history = false; onInsight(id) }, onDeleteConversation)
}

/** The text field scrolls internally; a long draft cannot push messages and the send button off screen. */
@Composable private fun CoachDraftInput(value: String, onChange: (String) -> Unit, label: String, compact: Boolean, modifier: Modifier) {
    BasicTextField(value, onChange, modifier = modifier.heightIn(min = Space.Action, max = if (compact) Space.WeightField else Space.ComposerMax)
        .clip(AppShapes.Control).background(AppColors.Input)
        .border(Space.Hairline, AppColors.Line.copy(alpha = .55f), AppShapes.Control).padding(Space.Regular)
        .semantics { contentDescription = label }, maxLines = if (compact) 2 else 4,
        textStyle = AppType.Body.copy(color = AppColors.Text), cursorBrush = SolidColor(AppColors.Accent),
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) Text(label, style = AppType.Body, color = AppColors.Secondary)
                field()
            }
        })
}

@Composable private fun CoachHistoryDialog(state: LocalState, onDismiss: () -> Unit, onNew: () -> Unit,
    onConversation: (String) -> Unit, onInsight: (String) -> Unit, onDelete: (String) -> Unit) {
    // Drafts keep their owner for restoration, but only sent messages belong in history.
    val conversationIds = remember(state.messages) {
        state.messages.filter { it.state != "draft" && it.role in listOf("user", "assistant") && it.content.isNotBlank() }
            .mapTo(mutableSetOf()) { it.conversationId }
    }
    val conversations = state.conversations.filter { it.id != "local-draft" && it.id in conversationIds }.sortedByDescending { it.createdAt }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Panel(Modifier.fillMaxWidth().heightIn(max = Space.MaxContent)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Диалоги и разборы", style = AppType.Heading, color = AppColors.Text, modifier = Modifier.weight(1f))
                IconAction(Icons.Default.Close, "Закрыть историю", onDismiss)
            }
            ActionButton("Новый диалог", onNew, Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                items(conversations, key = { "conversation:${it.id}" }) { conversation ->
                    CoachHistoryItem(conversation, { onConversation(conversation.id) }, { deletingId = conversation.id })
                }
                items(state.insights.sortedByDescending { it.createdAt }, key = { "insight:${it.id}" }) { insight ->
                    ActionButton("${insight.period} · ${formatDate(insight.createdAt)}", { onInsight(insight.id) }, Modifier.fillMaxWidth())
                }
                if (conversations.isEmpty() && state.insights.isEmpty()) item {
                    Text("Здесь появятся сохранённые диалоги и разборы.", style = AppType.Body, color = AppColors.Secondary)
                }
            }
        }
    }
    conversations.find { it.id == deletingId }?.let { conversation ->
        AppDialog("Удалить диалог?", { deletingId = null }) {
            Text("«${conversation.title}» и все его сообщения будут удалены.", style = AppType.Body, color = AppColors.Secondary)
            ActionButton("Удалить", { deletingId = null; onDelete(conversation.id) }, Modifier.fillMaxWidth(), primary = true)
            ActionButton("Отмена", { deletingId = null }, Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun CoachHistoryItem(conversation: CoachConversation, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val menuVisible = remember { MutableTransitionState(false) }
    LaunchedEffect(menuOpen) { menuVisible.targetState = menuOpen }
    Box(Modifier.fillMaxWidth()) {
        ActionButton(conversation.title, onOpen, Modifier.fillMaxWidth(),
            onLongClick = { menuOpen = true }, onLongClickLabel = "Действия диалога")
        if (menuVisible.currentState || menuVisible.targetState) {
            Popup(popupPositionProvider = CoachMenuPosition, onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true)) {
                AnimatedVisibility(menuVisible, enter = fadeIn(tween(Motion.Duration)), exit = fadeOut(tween(Motion.Duration))) {
                    Panel(Modifier.width(IntrinsicSize.Max).widthIn(min = Space.ContextMenuMin), outlined = true) {
                        ActionButton("Удалить", { menuOpen = false; onDelete() }, Modifier.fillMaxWidth(), icon = Icons.Default.DeleteOutline)
                    }
                }
            }
        }
    }
}

/** Keep the menu next to its row and inside the window, including the last visible row. */
private object CoachMenuPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val y = if (anchorBounds.bottom + popupContentSize.height <= windowSize.height) anchorBounds.bottom
            else anchorBounds.top - popupContentSize.height
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

@Composable private fun CoachProposalCard(proposal: PlanProposal, exercises: List<Exercise>, onOpen: () -> Unit,
    onApply: (() -> Unit)?, onReject: (() -> Unit)?) {
    var reason by rememberSaveable(proposal.proposalId) { mutableStateOf(false) }
    val current = proposal.status == "proposed" && proposal.expiresAt > System.currentTimeMillis()
    Panel(Modifier.fillMaxWidth(), outlined = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
            Icon(Icons.Default.AutoAwesome, null, tint = AppColors.Accent, modifier = Modifier.size(Space.Icon))
            Text(if (current) "Предложение" else if (proposal.status == "proposed") "Устарело" else proposalStatus(proposal.status), style = AppType.Label, color = AppColors.Accent)
        }
        Text(proposal.summary, style = AppType.Heading, color = AppColors.Text)
        proposal.operations.forEach { operation ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                Text(exercises.find { it.id == operation.exerciseId }?.name ?: operationLabel(operation.type), style = AppType.Label, color = AppColors.Text)
                Text("${operationValue(operation.type, operation.previous, exercises)} → ${operationValue(operation.type, operation.value, exercises)}",
                    style = AppType.Heading, color = AppColors.Accent)
                DividerLine()
            }
        }
        Text(if (proposal.scope == "session") "Только это занятие" else "Будущие занятия", style = AppType.Label, color = AppColors.Secondary)
        if (current) {
            ActionButton("Применить", onApply ?: onOpen, Modifier.fillMaxWidth(), primary = true)
            if (onReject != null) ActionButton("Оставить как есть", onReject, Modifier.fillMaxWidth())
        } else if (proposal.status in listOf("stale", "proposed")) Text("Записи изменились или прошло много времени. Попросите новое предложение.", style = AppType.Body, color = AppColors.Secondary)
        Chip(if (reason) "Скрыть причину" else "Почему так?") { reason = !reason }
        if (reason) {
            Text(proposal.reason, style = AppType.Body, color = AppColors.Secondary)
            ActionButton("Все подробности", onOpen, Modifier.fillMaxWidth())
        }
    }
}

fun formatDate(epoch: Long): String = java.time.Instant.ofEpochMilli(epoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
private fun messageState(state: String): String = when (state) { "sending" -> "Отправляется"; "cancelled" -> "Остановлено"; "error" -> "Ответ не получен"; "streaming" -> "Получение ответа"; else -> "Сохранено" }

@Preview(name = "Тренер", widthDp = 390, heightDp = 850)
@Composable private fun CoachPreview() = WorkoutsTheme { CoachScreen(PreviewFixtures.state, false, "idle", "", null, "Программа · неделя 1", {}, {}, {}, {}, null, {}, {}, {}) }
