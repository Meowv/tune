package com.meowv.tune.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.update.LocalVersion
import com.meowv.tune.update.UpdateFailure
import com.meowv.tune.update.UpdateInfo
import com.meowv.tune.update.UpdateUiState
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTextStyle

/**
 * 版本与更新页：从设置进入的独立子页，覆盖整个外壳（含播放器与导航），
 * 由系统返回键或左上返回键退出，不改变主菜单结构。
 */
@Composable
internal fun UpdateScreen(
    isCar: Boolean,
    local: LocalVersion,
    state: UpdateUiState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    Column(modifier.testTag("page_update")) {
        UpdateTopBar(isCar, onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (isCar) 40.dp else 24.dp,
                    vertical = if (isCar) 20.dp else 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                BrandHeader(isCar, local)
                Spacer(Modifier.height(if (isCar) 20.dp else 16.dp))
                StatusCard(isCar, state, onCheck)
                Spacer(Modifier.height(if (isCar) 16.dp else 12.dp))
                VersionCard(isCar, local, state.latest)
                Spacer(Modifier.height(if (isCar) 16.dp else 12.dp))
                ReleaseNotesCard(isCar, state.latest)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun UpdateTopBar(isCar: Boolean, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.update_back)
    Row(
        Modifier.fillMaxWidth().height(if (isCar) 64.dp else 56.dp)
            .padding(horizontal = if (isCar) 16.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack)
                .testTag("update_back")
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center
        ) {
            TuneIcon(R.drawable.ic_arrow_back, Modifier.size(22.dp), TuneColors.Text)
        }
        Spacer(Modifier.width(TuneSpacing.Hairline))
        Text(
            stringResource(R.string.update_title),
            color = TuneColors.Text,
            fontSize = if (isCar) 20.sp else 18.sp,
            fontWeight = FontWeight.SemiBold,
            style = TuneTextStyle,
            maxLines = 1
        )
    }
}

@Composable
private fun BrandHeader(isCar: Boolean, local: LocalVersion) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BrandLogo(Modifier.size(if (isCar) 84.dp else 72.dp))
        Spacer(Modifier.height(TuneSpacing.Medium))
        Text(
            stringResource(R.string.brand_full),
            color = TuneColors.Text,
            fontSize = if (isCar) 22.sp else 20.sp,
            fontWeight = FontWeight.SemiBold,
            style = TuneTextStyle,
            maxLines = 1
        )
        Spacer(Modifier.height(TuneSpacing.Hairline))
        Text(
            stringResource(R.string.update_tagline),
            color = TuneColors.Muted,
            fontSize = if (isCar) 15.sp else 13.sp,
            style = TuneTextStyle
        )
        Spacer(Modifier.height(TuneSpacing.Medium))
        Surface(color = TuneColors.Raised, shape = TuneShapes.Chip) {
            Text(
                "v${local.versionName}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = TuneColors.Primary,
                fontSize = if (isCar) 14.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                style = TuneTextStyle
            )
        }
    }
}

@Composable
private fun StatusCard(isCar: Boolean, state: UpdateUiState, onCheck: () -> Unit) {
    val title: String
    val body: String
    when (state) {
        UpdateUiState.Idle -> {
            title = stringResource(R.string.update_status_idle_title)
            body = stringResource(R.string.update_status_idle_body)
        }
        UpdateUiState.Checking -> {
            title = stringResource(R.string.update_status_checking_title)
            body = stringResource(R.string.update_status_checking_body)
        }
        is UpdateUiState.UpToDate -> {
            title = stringResource(R.string.update_status_latest_title)
            body = stringResource(R.string.update_status_latest_body)
        }
        is UpdateUiState.UpdateAvailable -> {
            title = stringResource(R.string.update_status_available_title, state.info.versionName)
            body = stringResource(R.string.update_status_available_body)
        }
        is UpdateUiState.Downgrade -> {
            title = stringResource(R.string.update_status_downgrade_title)
            body = stringResource(R.string.update_status_downgrade_body)
        }
        is UpdateUiState.Error -> {
            title = stringResource(R.string.update_status_error_title)
            body = updateFailureText(state.failure)
        }
    }
    UpdateCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(isCar, state)
            Spacer(Modifier.width(TuneSpacing.Medium))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    modifier = Modifier.testTag("update_status_title"),
                    color = statusTitleColor(state),
                    fontSize = if (isCar) 19.sp else 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TuneTextStyle
                )
                Spacer(Modifier.height(TuneSpacing.Hairline))
                Text(
                    body,
                    color = TuneColors.Muted,
                    fontSize = if (isCar) 14.sp else 13.sp,
                    lineHeight = if (isCar) 20.sp else 19.sp,
                    style = TuneTextStyle
                )
            }
        }
        Spacer(Modifier.height(TuneSpacing.Large))
        CheckButton(isCar, state.isChecking, onCheck)
    }
}

@Composable
private fun StatusIcon(isCar: Boolean, state: UpdateUiState) {
    val size = if (isCar) 30.dp else 28.dp
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        when (state) {
            UpdateUiState.Checking -> CircularProgressIndicator(
                Modifier.size(if (isCar) 24.dp else 22.dp),
                color = TuneColors.Primary,
                strokeWidth = 2.dp,
                trackColor = TuneColors.Primary.copy(alpha = .22f),
                strokeCap = StrokeCap.Round
            )
            is UpdateUiState.UpToDate -> TuneIcon(R.drawable.ic_ui_check, Modifier.size(size), TuneColors.Primary)
            is UpdateUiState.UpdateAvailable -> TuneIcon(R.drawable.ic_update, Modifier.size(size), TuneColors.Primary)
            is UpdateUiState.Error -> TuneIcon(R.drawable.ic_ui_info, Modifier.size(size), TuneColors.Error)
            is UpdateUiState.Downgrade -> TuneIcon(R.drawable.ic_ui_info, Modifier.size(size), TuneColors.Muted)
            UpdateUiState.Idle -> TuneIcon(R.drawable.ic_update, Modifier.size(size), TuneColors.Muted)
        }
    }
}

private fun statusTitleColor(state: UpdateUiState): Color = when (state) {
    is UpdateUiState.UpdateAvailable -> TuneColors.Primary
    is UpdateUiState.Error -> TuneColors.Error
    else -> TuneColors.Text
}

@Composable
private fun CheckButton(isCar: Boolean, checking: Boolean, onCheck: () -> Unit) {
    val label = stringResource(if (checking) R.string.update_checking else R.string.update_check)
    Box(
        Modifier.fillMaxWidth().height(if (isCar) 52.dp else 48.dp)
            .clip(TuneShapes.Control)
            .background(if (checking) TuneColors.Primary.copy(alpha = .16f) else TuneColors.Primary)
            .clickable(enabled = !checking, role = Role.Button, onClick = onCheck)
            .testTag("update_check")
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (checking) TuneColors.Primary else TuneColors.Background,
            fontSize = if (isCar) 16.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            style = TuneTextStyle
        )
    }
}

@Composable
private fun VersionCard(isCar: Boolean, local: LocalVersion, info: UpdateInfo?) {
    UpdateCard {
        CardTitle(stringResource(R.string.update_section_version), isCar)
        Spacer(Modifier.height(TuneSpacing.Small))
        InfoRow(stringResource(R.string.update_label_current), "v${local.versionName}")
        InfoRow(stringResource(R.string.update_label_version_code), local.versionCode.toString())
        info?.let {
            InfoRow(stringResource(R.string.update_label_latest), "v${it.versionName}", TuneColors.Primary)
            InfoRow(stringResource(R.string.update_label_release_date), it.releaseDate)
            InfoRow(stringResource(R.string.update_label_channel), it.channel)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = TuneColors.Text) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = TuneColors.Muted,
            fontSize = 13.sp,
            style = TuneTextStyle,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            color = valueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            style = TuneTextStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReleaseNotesCard(isCar: Boolean, info: UpdateInfo?) {
    UpdateCard {
        CardTitle(stringResource(R.string.update_section_notes), isCar)
        Spacer(Modifier.height(TuneSpacing.Small))
        if (info == null) {
            Text(
                stringResource(R.string.update_notes_empty),
                color = TuneColors.Muted,
                fontSize = 13.sp,
                style = TuneTextStyle
            )
            return@UpdateCard
        }
        if (info.title.isNotBlank()) {
            Text(
                info.title,
                color = TuneColors.Text,
                fontSize = if (isCar) 16.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                style = TuneTextStyle
            )
            Spacer(Modifier.height(TuneSpacing.Small))
        }
        if (info.releaseNotes.isEmpty()) {
            Text(
                stringResource(R.string.update_notes_empty),
                color = TuneColors.Muted,
                fontSize = 13.sp,
                style = TuneTextStyle
            )
        } else {
            info.releaseNotes.forEach { note ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("·", color = TuneColors.Primary, fontSize = 13.sp, style = TuneTextStyle)
                    Spacer(Modifier.width(TuneSpacing.Small))
                    Text(
                        note,
                        color = TuneColors.Muted,
                        fontSize = if (isCar) 14.sp else 13.sp,
                        lineHeight = if (isCar) 20.sp else 19.sp,
                        style = TuneTextStyle,
                        textAlign = TextAlign.Start
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = TuneColors.Surface, shape = TuneShapes.SettingsGroup) {
        Column(Modifier.fillMaxWidth().padding(20.dp), content = content)
    }
}

@Composable
private fun CardTitle(text: String, isCar: Boolean) {
    Text(
        text,
        color = TuneColors.Text,
        fontSize = if (isCar) 17.sp else 16.sp,
        fontWeight = FontWeight.SemiBold,
        style = TuneTextStyle
    )
}

@Composable
private fun updateFailureText(failure: UpdateFailure): String = stringResource(
    when (failure) {
        UpdateFailure.OFFLINE -> R.string.update_failure_offline
        UpdateFailure.TIMEOUT -> R.string.update_failure_timeout
        UpdateFailure.SERVER -> R.string.update_failure_server
        UpdateFailure.MALFORMED -> R.string.update_failure_malformed
        UpdateFailure.UNSUPPORTED_SCHEMA -> R.string.update_failure_schema
        UpdateFailure.PACKAGE_MISMATCH -> R.string.update_failure_package
        UpdateFailure.CHANNEL_MISMATCH -> R.string.update_failure_channel
        UpdateFailure.INCOMPATIBLE -> R.string.update_failure_incompatible
    }
)
