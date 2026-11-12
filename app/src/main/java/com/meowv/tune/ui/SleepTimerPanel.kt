package com.meowv.tune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    val remaining = if (timer.isActive) sleepRemainingLabel(timer.remainingMillis) else null
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = TuneColors.Surface,
        contentColor = TuneColors.Text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TuneColors.Border) }
    ) {
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
                    .padding(horizontal = 24.dp, vertical = 8.dp)
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
}

@Composable
private fun SleepTimerPreset(
    minutes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.heightIn(min = 50.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        TuneColors.Raised.copy(alpha = .96f),
                        TuneColors.Player.copy(alpha = .86f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        TuneColors.PrimaryLight.copy(alpha = .28f),
                        TuneColors.BorderSoft.copy(alpha = .7f)
                    )
                ),
                shape = shape
            )
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("sleep_timer_$minutes")
            .semantics {
                role = Role.Button
                contentDescription = "$minutes 分钟"
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
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by rememberSaveable { mutableStateOf("") }
    val minutes = value.toIntOrNull()
    val valid = minutes != null && minutes in 1..480
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.sleep_timer_custom_body),
                    color = TuneColors.Muted,
                    fontSize = 14.sp,
                    style = TuneTextStyle
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        value = input.filter(Char::isDigit).take(3)
                    },
                    label = { Text(stringResource(R.string.sleep_timer_minutes_label)) },
                    singleLine = true,
                    isError = value.isNotBlank() && !valid,
                    supportingText = {
                        Text(
                            stringResource(
                                if (value.isNotBlank() && !valid) {
                                    R.string.sleep_timer_custom_invalid
                                } else {
                                    R.string.sleep_timer_custom_range
                                }
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("sleep_timer_custom_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(requireNotNull(minutes)) },
                modifier = Modifier.testTag("sleep_timer_custom_confirm")
            ) {
                Text(stringResource(R.string.sleep_timer_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = TuneColors.Surface,
        textContentColor = TuneColors.Text,
        titleContentColor = TuneColors.Text
    )
}
