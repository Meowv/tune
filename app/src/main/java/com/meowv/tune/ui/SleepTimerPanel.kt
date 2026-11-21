package com.meowv.tune.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.meowv.tune.R
import com.meowv.tune.playback.SleepTimerState
import com.meowv.tune.playback.sleepTimerRemainingMinutes
import com.meowv.tune.ui.theme.TuneBrushes
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTypography

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
            TimerDialogChrome(isCar = true)
            Surface(
                modifier = Modifier.widthIn(max = 500.dp).fillMaxWidth(.94f),
                shape = TuneShapes.Dialog,
                color = TuneColors.Surface,
                border = BorderStroke(1.dp, TuneColors.Border)
            ) { content() }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 27.dp, topEnd = 27.dp),
            containerColor = TuneColors.Raised,
            contentColor = TuneColors.Text,
            scrimColor = TuneColors.Background.copy(alpha = .72f),
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
        Modifier.fillMaxWidth().background(if (isCar) TuneBrushes.Dialog else TuneBrushes.Card),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = TuneSpacing.Section, end = TuneSpacing.Section,
                    top = if (isCar) TuneSpacing.Section else TuneSpacing.Hairline,
                    bottom = TuneSpacing.Section
                )
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
                    modifier = Modifier.semantics { heading() },
                    style = TuneTypography.pageTitle(isCar)
                )
            }
            if (remaining != null) {
                Spacer(Modifier.height(5.dp))
                Text(
                    stringResource(R.string.sleep_timer_remaining_prefix, remaining),
                    color = TuneColors.PrimaryLight,
                    style = TuneTypography.secondary(isCar)
                )
            }
            Spacer(Modifier.height(TuneSpacing.Large))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val minPresetWidth = (if (isCar) 75.dp else 62.dp) * LocalDensity.current.fontScale
                val count = if (maxWidth >= minPresetWidth * 4 + TuneSpacing.Small * 3) 4 else 2
                Column(verticalArrangement = Arrangement.spacedBy(TuneSpacing.Small)) {
                    listOf(15, 30, 45, 60).chunked(count).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Small)) {
                            row.forEach { minutes ->
                                SleepTimerPreset(
                                    minutes = minutes,
                                    selected = timer.durationMinutes == minutes,
                                    isCar = isCar,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onPreset(minutes) }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(TuneSpacing.Small))
            SleepTimerMenuRow(
                isCar = isCar,
                label = stringResource(R.string.sleep_timer_custom),
                tag = "sleep_timer_custom",
                onClick = onCustom
            )
            if (timer.isActive) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = TuneSpacing.Hairline),
                    color = TuneColors.Divider
                )
                SleepTimerMenuRow(
                    isCar = isCar,
                    label = stringResource(R.string.sleep_timer_cancel),
                    tag = "sleep_timer_cancel",
                    destructive = true,
                    onClick = onCancelTimer
                )
            }
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
    var focused by remember { mutableStateOf(false) }
    val shape = TuneShapes.Control
    Box(
        modifier.heightIn(min = if (isCar) TuneSizes.TouchCar else 53.dp)
            .clip(shape)
            .background(if (selected) TuneColors.Segment else TuneColors.Raised)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> TuneColors.PrimaryLight
                    selected -> TuneColors.Primary
                    else -> TuneColors.Border
                },
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("sleep_timer_$minutes")
            .semantics { this.selected = selected }
            .padding(horizontal = TuneSpacing.Hairline, vertical = TuneSpacing.Small),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.sleep_timer_minutes, minutes),
            color = if (selected) TuneColors.PrimaryLight else TuneColors.Text,
            style = TuneTypography.control(isCar)
        )
    }
}

@Composable
private fun SleepTimerMenuRow(
    isCar: Boolean,
    label: String,
    tag: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch)
            .clip(TuneShapes.Control)
            .then(if (focused) Modifier.border(2.dp, TuneColors.Primary, TuneShapes.Control) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = label }
            .padding(horizontal = TuneSpacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (destructive) TuneColors.Error else TuneColors.Text,
            modifier = Modifier.weight(1f),
            style = TuneTypography.control(isCar)
        )
        if (!destructive) {
            TuneIcon(R.drawable.ic_chevron_right, Modifier.size(TuneSizes.IconSm), TuneColors.Muted)
        }
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
    val minutesLabel = stringResource(R.string.sleep_timer_minutes_label)
    val actionHeight = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch
    val input: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            value = value,
            onValueChange = { input -> value = input.filter(Char::isDigit).take(3) },
            textStyle = TuneTypography.control(isCar).copy(fontSize = if (isCar) 20.sp else 18.sp),
            suffix = {
                Text(
                    minutesLabel, color = TuneColors.Muted, style = TuneTypography.control(isCar),
                    modifier = Modifier.clearAndSetSemantics { }
                )
            },
            singleLine = true,
            isError = value.isNotBlank() && !valid,
            supportingText = {
                Text(
                    stringResource(
                        if (value.isNotBlank() && !valid) R.string.sleep_timer_custom_invalid
                        else R.string.sleep_timer_custom_range
                    ),
                    style = TuneTypography.meta(isCar)
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(requireNotNull(minutes)) }),
            shape = TuneShapes.Chip,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = TuneColors.Background,
                unfocusedContainerColor = TuneColors.Background,
                errorContainerColor = TuneColors.Background,
                focusedBorderColor = TuneColors.Primary,
                unfocusedBorderColor = TuneColors.Border,
                unfocusedSupportingTextColor = TuneColors.Muted,
                focusedSupportingTextColor = TuneColors.Muted
            ),
            modifier = modifier.heightIn(min = actionHeight)
                .semantics { contentDescription = minutesLabel }
                .testTag("sleep_timer_custom_input")
        )
    }
    val confirm: @Composable () -> Unit = {
        Button(
            enabled = valid,
            onClick = { onConfirm(requireNotNull(minutes)) },
            modifier = Modifier.heightIn(min = actionHeight).testTag("sleep_timer_custom_confirm"),
            shape = TuneShapes.Control,
            colors = ButtonDefaults.buttonColors(
                containerColor = TuneColors.Primary,
                contentColor = TuneColors.Background,
                disabledContainerColor = TuneColors.Segment,
                disabledContentColor = TuneColors.Muted
            )
        ) { Text(stringResource(R.string.sleep_timer_start), style = TuneTypography.control(isCar)) }
    }
    val dismiss: @Composable () -> Unit = {
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.heightIn(min = actionHeight),
            shape = TuneShapes.Control,
            border = BorderStroke(1.dp, TuneColors.Border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TuneColors.Text)
        ) {
            Text(stringResource(R.string.action_cancel), style = TuneTypography.control(isCar))
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        TimerDialogChrome(isCar)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
            BoxWithConstraints(
                Modifier.fillMaxSize().safeDrawingPadding()
                    .padding(horizontal = TuneSpacing.Section, vertical = TuneSpacing.Small),
                contentAlignment = Alignment.Center
            ) {
                val compact = isCar && maxWidth >= 440.dp && maxHeight < 224.dp
                Surface(
                    Modifier.widthIn(max = if (isCar) 580.dp else 420.dp).fillMaxWidth(),
                    shape = TuneShapes.Dialog,
                    color = TuneColors.Surface,
                    contentColor = TuneColors.Text,
                    border = BorderStroke(1.dp, TuneColors.Border)
                ) {
                    Column(
                        Modifier.background(TuneBrushes.Dialog).verticalScroll(rememberScrollState())
                            .padding(
                                horizontal = if (compact) TuneSpacing.Large else TuneSpacing.Section,
                                vertical = if (compact) TuneSpacing.Small else TuneSpacing.Section
                            ),
                        verticalArrangement = Arrangement.spacedBy(if (compact) TuneSpacing.Hairline else TuneSpacing.Medium)
                    ) {
                        Text(
                            stringResource(R.string.sleep_timer_custom_title),
                            color = TuneColors.Text,
                            style = if (compact) TuneTypography.rowTitle(true).copy(fontWeight = FontWeight.SemiBold)
                            else TuneTypography.pageTitle(isCar),
                            modifier = Modifier.semantics { heading() }
                        )
                        if (compact) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Small)
                            ) {
                                input(Modifier.weight(1f))
                                dismiss()
                                confirm()
                            }
                        } else {
                            input(Modifier.fillMaxWidth())
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Small, Alignment.End)
                            ) {
                                dismiss()
                                confirm()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerDialogChrome(isCar: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, isCar) {
        val window = (view.parent as DialogWindowProvider).window
        window.setDimAmount(.72f)
        if (isCar) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                show(WindowInsetsCompat.Type.statusBars())
                hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
        onDispose { }
    }
}
