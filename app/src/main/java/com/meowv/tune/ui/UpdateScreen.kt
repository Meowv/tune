package com.meowv.tune.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.update.LocalVersion
import com.meowv.tune.update.UpdateFailure
import com.meowv.tune.update.UpdateInfo
import com.meowv.tune.update.UpdateUiState
import com.meowv.tune.update.install.InstallFailure
import com.meowv.tune.update.install.InstallState
import com.meowv.tune.ui.theme.TuneBrushes
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneShapes
import com.meowv.tune.ui.theme.TuneSizes
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTypography

@Composable
internal fun UpdateScreen(
    isCar: Boolean,
    local: LocalVersion,
    state: UpdateUiState,
    install: InstallState,
    upgradedTo: String?,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    var notesExpanded by rememberSaveable(state.latest?.versionCode, state.latest?.versionName) { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    BoxWithConstraints(
        modifier.testTag("page_update").padding(horizontal = if (isCar) TuneSpacing.Section else TuneSpacing.Large),
        contentAlignment = Alignment.TopCenter
    ) {
        val twoColumns = maxWidth >= (if (isCar) 620.dp else 840.dp)
        val status: @Composable () -> Unit = {
            StatusCard(isCar, state, install, upgradedTo, onCheck, onDownload, onCancelDownload, onInstall)
        }
        val notes: @Composable () -> Unit = {
            ReleaseNotesCard(isCar, state.latest, notesExpanded) { notesExpanded = !notesExpanded }
        }
        Column(Modifier.widthIn(max = if (twoColumns) 1080.dp else 720.dp).fillMaxSize()) {
            UpdateTopBar(isCar, onBack)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(top = TuneSpacing.Small, bottom = TuneSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(TuneSpacing.Large)
            ) {
                if (twoColumns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Large)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TuneSpacing.Large)) {
                            BrandHeader(isCar, local)
                            VersionCard(isCar, local, state.latest)
                        }
                        Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(TuneSpacing.Large)) {
                            status()
                            notes()
                        }
                    }
                } else {
                    BrandHeader(isCar, local)
                    status()
                    VersionCard(isCar, local, state.latest)
                    notes()
                }
            }
        }
    }
}

@Composable
private fun UpdateTopBar(isCar: Boolean, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.update_back)
    var focused by remember { mutableStateOf(false) }
    val shape = TuneShapes.Chip
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (isCar) 64.dp else 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(if (isCar) TuneSizes.TouchCar else TuneSizes.Touch).clip(shape)
                .background(TuneColors.Raised)
                .border(if (focused) 2.dp else 1.dp, if (focused) TuneColors.Primary else TuneColors.Border, shape)
                .onFocusChanged { focused = it.isFocused }
                .clickable(role = Role.Button, onClick = onBack)
                .testTag("update_back")
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center
        ) {
            TuneIcon(R.drawable.ic_arrow_back, Modifier.size(TuneSizes.IconMd), TuneColors.Text)
        }
        Spacer(Modifier.width(TuneSpacing.Medium))
        Text(
            stringResource(R.string.update_title),
            color = TuneColors.Text,
            style = TuneTypography.pageTitle(isCar),
            modifier = Modifier.semantics { heading() }
        )
    }
}

@Composable
private fun BrandHeader(isCar: Boolean, local: LocalVersion) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Hairline),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painterResource(R.drawable.ic_meowvtune_splash),
            contentDescription = null,
            modifier = Modifier.size(if (isCar) 96.dp else 84.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TuneSpacing.Hairline)) {
            Text(
                stringResource(R.string.brand_full),
                color = TuneColors.Text,
                style = TuneTypography.pageTitle(isCar)
            )
            Text(
                stringResource(R.string.update_current_version, local.versionName),
                color = TuneColors.PrimaryLight,
                style = TuneTypography.control(isCar)
            )
        }
    }
}

@Composable
private fun StatusCard(
    isCar: Boolean,
    state: UpdateUiState,
    install: InstallState,
    upgradedTo: String?,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val context = LocalContext.current
    val presentation = statusPresentation(state, install, upgradedTo)
    UpdateCard(isCar, highlighted = true) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(if (isCar) 52.dp else 44.dp)
                    .background(presentation.tint.copy(alpha = .1f), TuneShapes.Control),
                contentAlignment = Alignment.Center
            ) {
                StatusIcon(isCar, state, install)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TuneSpacing.Hairline)) {
                Text(
                    presentation.title,
                    modifier = Modifier.testTag("update_status_title").semantics { liveRegion = LiveRegionMode.Polite },
                    color = presentation.tint,
                    style = TuneTypography.rowTitle(isCar).copy(
                        fontSize = if (isCar) 20.sp else 18.sp,
                        lineHeight = if (isCar) 28.sp else 25.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                presentation.body?.let { body ->
                    Text(
                        body,
                        color = TuneColors.Muted,
                        style = TuneTypography.secondary(isCar)
                    )
                }
            }
        }
        if (install is InstallState.Downloading) {
            Spacer(Modifier.height(TuneSpacing.Large))
            DownloadProgress(isCar, install)
        }
        Spacer(Modifier.height(TuneSpacing.Large))
        InstallAction(
            isCar = isCar,
            state = state,
            install = install,
            onCheck = onCheck,
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
            onInstall = onInstall,
            onGrantPermission = { openInstallPermission(context) }
        )
    }
}

@Composable
private fun DownloadProgress(isCar: Boolean, download: InstallState.Downloading) {
    val context = LocalContext.current
    val total = download.totalBytes
    val downloaded = download.downloadedBytes
    if (download.indeterminate) {
        val track = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
        if (rememberMotionEnabled()) {
            LinearProgressIndicator(
                modifier = track,
                color = TuneColors.Primary,
                trackColor = TuneColors.TrackOff
            )
        } else {
            Box(track.background(TuneColors.BorderSoft).progressSemantics()) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(.25f).background(TuneColors.Primary, CircleShape))
            }
        }
    } else {
        LinearProgressIndicator(
            progress = { download.percent / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                .testTag("update_progress"),
            color = TuneColors.Primary,
            trackColor = TuneColors.TrackOff
        )
    }
    Spacer(Modifier.height(TuneSpacing.Small))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (total > 0) "${download.percent}%" else "",
            color = TuneColors.Primary,
            style = TuneTypography.control(isCar).copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (total > 0) {
                stringResource(
                    R.string.update_download_size,
                    Formatter.formatShortFileSize(context, downloaded),
                    Formatter.formatShortFileSize(context, total)
                )
            } else {
                Formatter.formatShortFileSize(context, downloaded)
            },
            color = TuneColors.Muted,
            style = TuneTypography.secondary(isCar)
        )
    }
}

@Composable
private fun InstallAction(
    isCar: Boolean,
    state: UpdateUiState,
    install: InstallState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onGrantPermission: () -> Unit
) {
    val checking = state is UpdateUiState.Checking
    val available = state is UpdateUiState.UpdateAvailable
    when {
        install is InstallState.Downloading -> PrimaryButton(
            label = stringResource(R.string.update_cancel_download),
            enabled = true,
            primary = false,
            isCar = isCar,
            tag = "update_cancel",
            onClick = onCancelDownload
        )
        install is InstallState.Verifying -> PrimaryButton(
            label = stringResource(R.string.update_verifying_action),
            enabled = false,
            primary = true,
            isCar = isCar,
            tag = "update_check",
            onClick = {}
        )
        install is InstallState.Downloaded ||
            (install is InstallState.Failed && install.failure == InstallFailure.INSTALL_CANCELLED) -> PrimaryButton(
            label = stringResource(R.string.update_install_now),
            enabled = true,
            primary = true,
            isCar = isCar,
            tag = "update_install",
            onClick = onInstall
        )
        install is InstallState.InstallPermissionRequired -> PrimaryButton(
            label = stringResource(R.string.update_grant_permission),
            enabled = true,
            primary = true,
            isCar = isCar,
            tag = "update_permission",
            onClick = onGrantPermission
        )
        install is InstallState.Installing -> PrimaryButton(
            label = stringResource(R.string.update_installing_action),
            enabled = false,
            primary = true,
            isCar = isCar,
            tag = "update_check",
            onClick = {}
        )
        available -> PrimaryButton(
            label = stringResource(
                if (install is InstallState.Failed || install is InstallState.Cancelled) {
                    R.string.update_retry_download
                } else {
                    R.string.update_download
                }
            ),
            enabled = true,
            primary = true,
            isCar = isCar,
            tag = "update_download",
            onClick = onDownload
        )
        else -> PrimaryButton(
            label = stringResource(when (state) {
                UpdateUiState.Checking -> R.string.update_checking
                is UpdateUiState.UpToDate -> R.string.update_recheck
                is UpdateUiState.Error -> R.string.update_retry_check
                else -> R.string.update_check
            }),
            enabled = !checking,
            primary = true,
            isCar = isCar,
            tag = "update_check",
            icon = R.drawable.ic_ui_refresh,
            onClick = onCheck
        )
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    isCar: Boolean,
    tag: String,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit
) {
    val background = when {
        !primary -> TuneColors.Raised
        enabled -> TuneColors.Primary
        else -> TuneColors.DisabledContainer
    }
    val textColor = when {
        !primary -> TuneColors.Text
        enabled -> TuneColors.OnPrimary
        else -> TuneColors.DisabledContent
    }
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        border = if (focused) {
            BorderStroke(2.dp, if (primary && enabled) TuneColors.FocusOnAccent else TuneColors.Primary)
        } else {
            BorderStroke(1.dp, TuneColors.BorderStrong)
        },
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch)
            .onFocusChanged { focused = it.isFocused }.testTag(tag),
        shape = TuneShapes.Chip,
        colors = ButtonDefaults.buttonColors(
            containerColor = background, contentColor = textColor,
            disabledContainerColor = background, disabledContentColor = textColor
        )
    ) {
        if (icon != null) {
            TuneIcon(icon, Modifier.size(TuneSizes.IconSm), textColor)
            Spacer(Modifier.width(TuneSpacing.Small))
        }
        Text(
            label, style = TuneTypography.control(isCar).copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StatusIcon(isCar: Boolean, state: UpdateUiState, install: InstallState) {
    val size = if (isCar) 30.dp else 28.dp
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val spinning = state is UpdateUiState.Checking ||
            install is InstallState.Verifying ||
            install is InstallState.Installing
        if (spinning) {
            if (rememberMotionEnabled()) {
                CircularProgressIndicator(
                    Modifier.size(if (isCar) 24.dp else 22.dp),
                    color = TuneColors.Primary,
                    strokeWidth = 2.dp,
                    trackColor = TuneColors.TrackOff,
                    strokeCap = StrokeCap.Round
                )
            } else {
                TuneIcon(R.drawable.ic_ui_refresh, Modifier.size(size).progressSemantics(), TuneColors.Primary)
            }
        } else {
            when {
                install is InstallState.Failed -> TuneIcon(R.drawable.ic_ui_info, Modifier.size(size), TuneColors.Error)
                state is UpdateUiState.Error -> TuneIcon(R.drawable.ic_ui_info, Modifier.size(size), TuneColors.Error)
                state is UpdateUiState.UpToDate -> TuneIcon(R.drawable.ic_ui_check, Modifier.size(size), TuneColors.Primary)
                state is UpdateUiState.UpdateAvailable ->
                    TuneIcon(R.drawable.ic_update, Modifier.size(size), TuneColors.Primary)
                else -> TuneIcon(R.drawable.ic_update, Modifier.size(size), TuneColors.Muted)
            }
        }
    }
}

private data class StatusPresentation(val title: String, val body: String?, val tint: Color)

@Composable
private fun statusPresentation(
    state: UpdateUiState,
    install: InstallState,
    upgradedTo: String?
): StatusPresentation {
    // 下载/安装过程优先于「发现新版本」这类静态文案。
    when (install) {
        is InstallState.Downloading -> return StatusPresentation(
            stringResource(R.string.update_status_downloading_title),
            null,
            TuneColors.Text
        )
        is InstallState.Verifying -> return StatusPresentation(
            stringResource(R.string.update_status_verifying_title),
            null,
            TuneColors.Text
        )
        is InstallState.Downloaded -> return StatusPresentation(
            stringResource(R.string.update_status_downloaded_title),
            null,
            TuneColors.Primary
        )
        is InstallState.InstallPermissionRequired -> return StatusPresentation(
            stringResource(R.string.update_status_permission_title),
            stringResource(R.string.update_status_permission_body),
            TuneColors.Text
        )
        is InstallState.Installing -> return StatusPresentation(
            stringResource(R.string.update_status_installing_title),
            stringResource(R.string.update_status_installing_body),
            TuneColors.Text
        )
        is InstallState.Failed -> return StatusPresentation(
            stringResource(R.string.update_status_failed_title),
            installFailureText(install.failure),
            TuneColors.Error
        )
        is InstallState.Cancelled -> return StatusPresentation(
            stringResource(R.string.update_status_cancelled_title),
            null,
            TuneColors.Muted
        )
        InstallState.Idle -> Unit
    }
    if (state is UpdateUiState.UpToDate && upgradedTo != null) {
        return StatusPresentation(
            stringResource(R.string.update_installed_title, upgradedTo),
            null,
            TuneColors.Primary
        )
    }
    return when (state) {
        UpdateUiState.Idle -> StatusPresentation(
            stringResource(R.string.update_status_idle_title),
            null,
            TuneColors.Text
        )
        UpdateUiState.Checking -> StatusPresentation(
            stringResource(R.string.update_status_checking_title),
            null,
            TuneColors.Text
        )
        is UpdateUiState.UpToDate -> StatusPresentation(
            stringResource(R.string.update_status_latest_title),
            null,
            TuneColors.Text
        )
        is UpdateUiState.UpdateAvailable -> StatusPresentation(
            stringResource(R.string.update_status_available_title, state.info.versionName),
            null,
            TuneColors.Primary
        )
        is UpdateUiState.Error -> StatusPresentation(
            stringResource(R.string.update_status_error_title),
            updateFailureText(state.failure),
            TuneColors.Error
        )
    }
}

@Composable
private fun VersionCard(isCar: Boolean, local: LocalVersion, info: UpdateInfo?) {
    UpdateCard(isCar, Modifier.testTag("update_version_card")) {
        CardTitle(stringResource(R.string.update_section_version), isCar)
        Spacer(Modifier.height(TuneSpacing.Small))
        InfoRow(stringResource(R.string.update_label_version_code), local.versionCode.toString(), isCar)
        // 清单日期属于清单版本，不能把待升级版本的日期标到当前安装版本上。
        val currentRelease = info?.takeIf { it.versionCode == local.versionCode && it.versionName == local.versionName }
        InfoRow(
            stringResource(R.string.update_label_release_date),
            currentRelease?.releaseDate?.takeIf { it.isNotBlank() } ?: stringResource(R.string.update_value_unknown),
            isCar
        )
        InfoRow(
            stringResource(R.string.update_label_channel),
            if (local.channel == "stable") stringResource(R.string.update_channel_stable) else local.channel,
            isCar, showDivider = false
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    isCar: Boolean,
    showDivider: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = TuneSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = TuneColors.Muted,
            style = TuneTypography.secondary(isCar),
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            color = TuneColors.Text,
            style = TuneTypography.control(isCar),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
    if (showDivider) HorizontalDivider(color = TuneColors.Divider)
}

@Composable
private fun ReleaseNotesCard(isCar: Boolean, info: UpdateInfo?, expanded: Boolean, onToggle: () -> Unit) {
    val notes = info?.releaseNotes.orEmpty()
    UpdateCard(isCar) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TuneSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CardTitle(stringResource(R.string.update_section_notes), isCar, Modifier.weight(1f))
            if (info != null) {
                Text(
                    "v${info.versionName}", color = TuneColors.PrimaryLight,
                    style = TuneTypography.control(isCar),
                    modifier = Modifier.widthIn(max = 136.dp),
                    textAlign = TextAlign.End
                )
            }
        }
        Spacer(Modifier.height(TuneSpacing.Small))
        if (notes.isEmpty()) {
            Text(
                stringResource(R.string.update_notes_empty),
                color = TuneColors.Muted,
                style = TuneTypography.secondary(isCar)
            )
        } else {
            Column(if (rememberMotionEnabled()) Modifier.animateContentSize() else Modifier) {
                (if (expanded) notes else notes.take(5)).forEach { note ->
                    Text(
                        note, modifier = Modifier.fillMaxWidth().padding(vertical = TuneSpacing.Hairline),
                        color = TuneColors.Text,
                        style = TuneTypography.rowTitle(isCar).copy(fontWeight = FontWeight.Normal)
                    )
                }
            }
            if (notes.size > 5) {
                TextButton(
                    onClick = onToggle,
                    modifier = Modifier.heightIn(min = if (isCar) TuneSizes.TouchCar else TuneSizes.Touch)
                        .testTag("update_notes_toggle")
                ) {
                    Text(
                        stringResource(if (expanded) R.string.update_notes_collapse else R.string.update_notes_expand),
                        style = TuneTypography.control(isCar)
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(
    isCar: Boolean,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        color = TuneColors.Surface,
        shape = TuneShapes.Dialog,
        border = BorderStroke(1.dp, TuneColors.BorderStrong)
    ) {
        Column(
            Modifier.fillMaxWidth().then(if (highlighted) Modifier.background(TuneBrushes.Card) else Modifier)
                .padding(if (isCar) TuneSpacing.Section else TuneSpacing.Large),
            content = content
        )
    }
}

@Composable
private fun CardTitle(text: String, isCar: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        color = TuneColors.Text,
        style = TuneTypography.rowTitle(isCar).copy(fontWeight = FontWeight.SemiBold),
        modifier = modifier.semantics { heading() }
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

@Composable
private fun installFailureText(failure: InstallFailure): String = stringResource(
    when (failure) {
        InstallFailure.DOWNLOAD_FAILED -> R.string.update_fail_download
        InstallFailure.STORAGE -> R.string.update_fail_storage
        InstallFailure.FILE_MISSING -> R.string.update_fail_file_missing
        InstallFailure.SIZE_MISMATCH -> R.string.update_fail_size
        InstallFailure.CHECKSUM_MISMATCH -> R.string.update_fail_checksum
        InstallFailure.PACKAGE_MISMATCH -> R.string.update_fail_package
        InstallFailure.VERSION_MISMATCH -> R.string.update_fail_version
        InstallFailure.INCOMPATIBLE -> R.string.update_fail_incompatible
        InstallFailure.INSTALL_FAILED -> R.string.update_fail_install
        InstallFailure.INSTALL_CANCELLED -> R.string.update_fail_install_cancelled
    }
)

private fun openInstallPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
