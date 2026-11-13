package ru.dmitriy.workouts.feature.coach

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Kept above the sheet/full-screen boundary; each conversation owns its scroll anchor. */
@Stable
class CoachUiMemory {
    private val threads = linkedMapOf<String, CoachThreadUi>()

    internal fun thread(key: String): CoachThreadUi = threads.getOrPut(key) { CoachThreadUi() }

    companion object {
        val Saver = listSaver<CoachUiMemory, Any>(
            save = { memory ->
                memory.threads.flatMap { (key, value) ->
                    value.capturePosition()
                    listOf(key, value.list.firstVisibleItemIndex, value.list.firstVisibleItemScrollOffset,
                        value.followTail, value.changeMode, value.positioned)
                }
            },
            restore = { values ->
                CoachUiMemory().apply {
                    values.chunked(6).forEach { value ->
                        threads[value[0] as String] = CoachThreadUi(
                            LazyListState(value[1] as Int, value[2] as Int), value[3] as Boolean, value[4] as Boolean,
                            value[5] as Boolean,
                        )
                    }
                }
            },
        )
    }
}

@Stable
internal class CoachThreadUi(
    val list: LazyListState = LazyListState(),
    followTail: Boolean = true,
    changeMode: Boolean = false,
    positioned: Boolean = false,
) {
    var followTail by mutableStateOf(followTail)
    var changeMode by mutableStateOf(changeMode)
    var positioned = positioned
        private set
    private var observedIndex = list.firstVisibleItemIndex
    private var observedOffset = list.firstVisibleItemScrollOffset
    private var movingTowardTail = false

    /** Input takes ownership synchronously, before LazyColumn's requested anchor or layout changes. */
    fun stopFollowing() {
        positioned = true
        followTail = false
        movingTowardTail = false
        rememberAnchor()
    }

    fun followLatest() {
        positioned = true
        followTail = true
        movingTowardTail = false
        rememberAnchor()
    }

    /** Also runs on disposal, so a pending jump survives a sheet/route composition being removed. */
    fun capturePosition() {
        if (!positioned) {
            positioned = true
            rememberAnchor()
            return
        }
        val index = list.firstVisibleItemIndex
        val offset = list.firstVisibleItemScrollOffset
        val backward = index < observedIndex || (index == observedIndex && offset < observedOffset)
        val forward = index > observedIndex || (index == observedIndex && offset > observedOffset)
        val layout = list.layoutInfo
        val anchorItem = layout.visibleItemsInfo.find { it.index == index }
        val freshGeometry = anchorItem?.offset == -offset
        if (backward && (list.canScrollForward || !freshGeometry)) {
            followTail = false
            movingTowardTail = false
        } else if (forward && !followTail) movingTowardTail = true
        if (movingTowardTail && freshGeometry && !list.canScrollForward &&
            layout.visibleItemsInfo.lastOrNull()?.index == layout.totalItemsCount - 1) {
            followTail = true
            movingTowardTail = false
        }
        rememberAnchor()
    }

    /** Only the controller calls this after its own scroll, which never counts as a user gesture. */
    fun acknowledgeAutomaticPosition() {
        positioned = true
        movingTowardTail = false
        rememberAnchor()
    }

    private fun rememberAnchor() {
        observedIndex = list.firstVisibleItemIndex
        observedOffset = list.firstVisibleItemScrollOffset
    }
}

@Composable
fun rememberCoachUiMemory(): CoachUiMemory = rememberSaveable(saver = CoachUiMemory.Saver) { CoachUiMemory() }
