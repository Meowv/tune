package com.meowv.tune.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
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
import com.meowv.tune.update.install.InstallFailure
import com.meowv.tune.update.install.InstallState
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
    install: InstallState,
    upgradedTo: String?,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
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
                StatusCard(
                    isCar = isCar,
                    state = state,
                    install = install,
                    upgradedTo = upgradedTo,
                    onCheck = onCheck,
                    onDownload = onDownload,
                    onCancelDownload = onCancelDownload,
                    onInstall = onInstall
                )
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
    UpdateCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(isCar, state, install)
            Spacer(Modifier.width(TuneSpacing.Medium))
            Column(Modifier.weight(1f)) {
                Text(
                    presentation.title,
                    modifier = Modifier.testTag("update_status_title"),
                    color = presentation.tint,
                    fontSize = if (isCar) 19.sp else 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TuneTextStyle
                )
                Spacer(Modifier.height(TuneSpacing.Hairline))
                Text(
                    presentation.body,
                    color = TuneColors.Muted,
                    fontSize = if (isCar) 14.sp else 13.sp,
                    lineHeight = if (isCar) 20.sp else 19.sp,
                    style = TuneTextStyle
                )
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
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = TuneColors.Primary,
            trackColor = TuneColors.Primary.copy(alpha = .18f)
        )
    } else {
        LinearProgressIndicator(
            progress = { download.percent / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                .testTag("update_progress"),
            color = TuneColors.Primary,
            trackColor = TuneColors.Primary.copy(alpha = .18f)
        )
    }
    Spacer(Modifier.height(TuneSpacing.Small))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (total > 0) "${download.percent}%" else "",
            color = TuneColors.Primary,
            fontSize = if (isCar) 15.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = TuneTextStyle
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
            fontSize = if (isCar) 14.sp else 12.sp,
            style = TuneTextStyle
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
        install is InstallState.Downloaded -> PrimaryButton(
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
            label = stringResource(if (checking) R.string.update_checking else R.string.update_check),
            enabled = !checking,
            primary = true,
            isCar = isCar,
            tag = "update_check",
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
    onClick: () -> Unit
) {
    val background = when {
        !primary -> TuneColors.Raised
        enabled -> TuneColors.Primary
        else -> TuneColors.Primary.copy(alpha = .16f)
    }
    val textColor = when {
        !primary -> TuneColors.Text
        enabled -> TuneColors.Background
        else -> TuneColors.Primary
    }
    Box(
        Modifier.fillMaxWidth().height(if (isCar) 52.dp else 48.dp)
            .clip(TuneShapes.Control)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = textColor,
            fontSize = if (isCar) 16.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            style = TuneTextStyle
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
            CircularProgressIndicator(
                Modifier.size(if (isCar) 24.dp else 22.dp),
                color = TuneColors.Primary,
                strokeWidth = 2.dp,
                trackColor = TuneColors.Primary.copy(alpha = .22f),
                strokeCap = StrokeCap.Round
            )
        } else {
            when {
                install is InstallState.Failed -> TuneIcon(R.drawable.ic_ui_info, Modifier.size(size), TuneColors.Error)
                state is UpdateUiState.Error -> TuneIcon(R.drawable.ic_ui_info, Modifier.size(size), TuneColors.Error)
                state is UpdateUiState.UpToDate -> TuneIcon(R.drawable.ic_ui_check, Modifier.size(size), TuneColors.Primary)
                state is UpdateUiState.UpdateAvailable ->
                    TuneIcon(R.drawable.ic_update, Modifier.size(size), TuneColors.Primary)
                state is UpdateUiState.Downgrade ->
                    TuneIcon(R.drawable.ic_ui_info, Modifier.size(size), TuneColors.Muted)
                else -> TuneIcon(R.drawable.ic_update, Modifier.size(size), TuneColors.Muted)
            }
        }
    }
}

private data class StatusPresentation(val title: String, val body: String, val tint: Color)

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
            if (install.indeterminate) {
                stringResource(R.string.update_status_downloading_unknown)
            } else {
                stringResource(R.string.update_status_downloading_body)
            },
            TuneColors.Text
        )
        is InstallState.Verifying -> return StatusPresentation(
            stringResource(R.string.update_status_verifying_title),
            stringResource(R.string.update_status_verifying_body),
            TuneColors.Text
        )
        is InstallState.Downloaded -> return StatusPresentation(
            stringResource(R.string.update_status_downloaded_title),
            stringResource(R.string.update_status_downloaded_body),
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
            stringResource(R.string.update_status_cancelled_body),
            TuneColors.Muted
        )
        InstallState.Idle -> Unit
    }
    if (state is UpdateUiState.UpToDate && upgradedTo != null) {
        return StatusPresentation(
            stringResource(R.string.update_installed_title, upgradedTo),
            stringResource(R.string.update_installed_body),
            TuneColors.Primary
        )
    }
    return when (state) {
        UpdateUiState.Idle -> StatusPresentation(
            stringResource(R.string.update_status_idle_title),
            stringResource(R.string.update_status_idle_body),
            TuneColors.Text
        )
        UpdateUiState.Checking -> StatusPresentation(
            stringResource(R.string.update_status_checking_title),
            stringResource(R.string.update_status_checking_body),
            TuneColors.Text
        )
        is UpdateUiState.UpToDate -> StatusPresentation(
            stringResource(R.string.update_status_latest_title),
            stringResource(R.string.update_status_latest_body),
            TuneColors.Text
        )
        is UpdateUiState.UpdateAvailable -> StatusPresentation(
            stringResource(R.string.update_status_available_title, state.info.versionName),
            stringResource(R.string.update_status_available_body),
            TuneColors.Primary
        )
        is UpdateUiState.Downgrade -> StatusPresentation(
            stringResource(R.string.update_status_downgrade_title),
            stringResource(R.string.update_status_downgrade_body),
            TuneColors.Text
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

@Composable
private fun installFailureText(failure: InstallFailure): String = stringResource(
    when (failure) {
        InstallFailure.DOWNLOAD_FAILED -> R.string.update_fail_download
        InstallFailure.FILE_MISSING -> R.string.update_fail_file_missing
        InstallFailure.SIZE_MISMATCH -> R.string.update_fail_size
        InstallFailure.CHECKSUM_MISMATCH -> R.string.update_fail_checksum
        InstallFailure.PACKAGE_MISMATCH -> R.string.update_fail_package
        InstallFailure.VERSION_MISMATCH -> R.string.update_fail_version
        InstallFailure.SIGNATURE_MISMATCH -> R.string.update_fail_signature
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
