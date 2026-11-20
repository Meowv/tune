package com.meowv.tune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.playback.SleepTimerState
import com.meowv.tune.playback.sleepTimerRemainingMinutes
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneTextStyle

@Composable
internal fun sleepRemainingLabel(remainingMillis: Long): String {
    val minutes = sleepTimerRemainingMinutes(remainingMillis)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.sleep_timer_remaining_minutes, minutes)
        rest == 0 -> stringResource(R.string.sleep_timer_remaining_hours, hours)
        else -> stringResource(R.string.sleep_timer_remaining_hours_minutes, hours, rest)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimerPanel(
    isCar: Boolean,
    timer: SleepTimerState,
    onDismiss: () -> Unit,
    onPreset: (Int) -> Unit,
    onCustom: () -> Unit,
    onCancelTimer: () -> Unit
) {
    val content: @Composable () -> Unit = {
        SleepTimerOptions(isCar, timer, onPreset, onCustom, onCancelTimer)
    }
    if (isCar) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.9f),
                shape = TuneShapes.Dialog,
                color = TuneColors.Surface
            ) { content() }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = TuneColors.Surface,
            contentColor = TuneColors.Text,
            dragHandle = { BottomSheetDefaults.DragHandle(color = TuneColors.Border) }
        ) { content() }
    }
}

@Composable
private fun SleepTimerOptions(
    isCar: Boolean,
    timer: SleepTimerState,
    onPreset: (Int) -> Unit,
    onCustom: () -> Unit,
    onCancelTimer: () -> Unit
) {
    val remaining = if (timer.isActive) sleepRemainingLabel(timer.remainingMillis) else null
    Box(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(
                listOf(
                    TuneColors.Surface,
                    TuneColors.Player.copy(alpha = .96f),
                    TuneColors.Background
                )
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (isCar) 20.dp else 8.dp)
                .testTag("sleep_timer_panel")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TuneIcon(
                    R.drawable.ic_sleep,
                    Modifier.size(24.dp),
                    TuneColors.PrimaryLight
                )
                Text(
                    stringResource(R.string.now_playing_sleep_timer),
                    color = TuneColors.Text,
                    fontSize = if (isCar) 23.sp else 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                    style = TuneTextStyle
                )
            }
            if (remaining != null) {
                Spacer(Modifier.height(5.dp))
                Text(
                    stringResource(R.string.sleep_timer_remaining_prefix, remaining),
                    color = TuneColors.PrimaryLight,
                    fontSize = 14.sp,
                    style = TuneTextStyle
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(15, 30, 45, 60).forEach { minutes ->
                    SleepTimerPreset(
                        minutes = minutes,
                        selected = timer.durationMinutes == minutes,
                        isCar = isCar,
                        modifier = Modifier.weight(1f),
                        onClick = { onPreset(minutes) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SleepTimerMenuRow(
                label = stringResource(R.string.sleep_timer_custom),
                tag = "sleep_timer_custom",
                onClick = onCustom
            )
            if (timer.isActive) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = TuneColors.Divider
                )
                SleepTimerMenuRow(
                    label = stringResource(R.string.sleep_timer_cancel),
                    tag = "sleep_timer_cancel",
                    destructive = true,
                    onClick = onCancelTimer
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SleepTimerPreset(
    minutes: Int,
    selected: Boolean,
    isCar: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.heightIn(min = if (isCar) 56.dp else 50.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        if (selected) TuneColors.Primary.copy(alpha = .28f) else TuneColors.Raised.copy(alpha = .96f),
                        TuneColors.Player.copy(alpha = .86f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        TuneColors.PrimaryLight.copy(alpha = if (selected) .85f else .28f),
                        TuneColors.BorderSoft.copy(alpha = .7f)
                    )
                ),
                shape = shape
            )
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("sleep_timer_$minutes")
            .semantics {
                role = Role.Button
                this.selected = selected
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.sleep_timer_minutes, minutes),
            color = TuneColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            style = TuneTextStyle
        )
    }
}

@Composable
private fun SleepTimerMenuRow(
    label: String,
    tag: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clip(TuneShapes.Control)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (destructive) TuneColors.Error else TuneColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            style = TuneTextStyle
        )
    }
}

@Composable
internal fun CustomSleepTimerDialog(
    isCar: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by rememberSaveable { mutableStateOf("") }
    val minutes = value.toIntOrNull()
    val valid = minutes != null && minutes in 1..480
    val input: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            value = value,
            onValueChange = { input -> value = input.filter(Char::isDigit).take(3) },
            label = { Text(stringResource(R.string.sleep_timer_minutes_label)) },
            singleLine = true,
            isError = value.isNotBlank() && !valid,
            supportingText = {
                Text(stringResource(
                    if (value.isNotBlank() && !valid) R.string.sleep_timer_custom_invalid
                    else R.string.sleep_timer_custom_range
                ))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(requireNotNull(minutes)) }),
            modifier = modifier.heightIn(min = if (isCar) 64.dp else 56.dp).testTag("sleep_timer_custom_input")
        )
    }
    val confirm: @Composable () -> Unit = {
        TextButton(
            enabled = valid,
            onClick = { onConfirm(requireNotNull(minutes)) },
            modifier = Modifier.heightIn(min = if (isCar) 56.dp else 48.dp).testTag("sleep_timer_custom_confirm")
        ) { Text(stringResource(R.string.sleep_timer_start)) }
    }
    val dismiss: @Composable () -> Unit = {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = if (isCar) 56.dp else 48.dp)) {
            Text(stringResource(R.string.action_cancel))
        }
    }
    val title = stringResource(R.string.sleep_timer_custom_title)
    if (isCar) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.matchParentSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
                BoxWithConstraints(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val compact = maxHeight < 160.dp
                    Surface(
                        Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                        shape = TuneShapes.Dialog, color = TuneColors.Surface, contentColor = TuneColors.Text
                    ) {
                        Column(
                            Modifier.verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 16.dp)
                        ) {
                            Text(title, fontSize = if (compact) 18.sp else 21.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                input(Modifier.weight(1f))
                                dismiss()
                                confirm()
                            }
                        }
                    }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(decorFitsSystemWindows = false),
            modifier = Modifier.safeDrawingPadding(),
            title = { Text(title) },
            text = { input(Modifier.fillMaxWidth()) },
            confirmButton = confirm,
            dismissButton = dismiss,
            containerColor = TuneColors.Surface,
            textContentColor = TuneColors.Text,
            titleContentColor = TuneColors.Text
        )
    }
}
