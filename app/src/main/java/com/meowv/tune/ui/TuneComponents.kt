package com.meowv.tune.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneTextStyle

@Composable
internal fun TuneIcon(@DrawableRes resource: Int, modifier: Modifier = Modifier, tint: Color = TuneColors.Primary) {
    Icon(painterResource(resource), contentDescription = null, modifier = modifier, tint = tint)
}

@Composable
internal fun BrandLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_brand), contentDescription = null, modifier = modifier)
}

@Composable
internal fun StationBadge(station: DemoStation, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * .2f)).background(station.color)
            .clearAndSetSemantics { }, contentAlignment = Alignment.Center
    ) {
        Text(station.id, color = TuneColors.Text, fontSize = (size.value * .32f).sp, fontWeight = FontWeight.Bold,
            style = TuneTextStyle, modifier = Modifier.padding(top = size * .04f))
        Box(Modifier.align(Alignment.TopEnd).padding(top = size * .08f, end = size * .12f)
            .size(size * .2f).background(TuneColors.Primary, CircleShape))
        Box(Modifier.align(Alignment.BottomStart).padding(start = size * .12f, bottom = size * .16f)
            .size(size * .12f).background(Color(0xFFD3C8FF), CircleShape))
    }
}

@Composable
internal fun LiveStatus(isCar: Boolean, modifier: Modifier = Modifier, color: Color = TuneColors.Live) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(if (isCar) 8.dp else 7.dp).background(TuneColors.Live, CircleShape))
        Text(stringResource(R.string.station_live_label), color = color, fontSize = if (isCar) 14.sp else 12.sp, lineHeight = if (isCar) 18.sp else 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = TuneTextStyle)
    }
}

@Composable
internal fun PreviewControl(
    @DrawableRes icon: Int,
    description: String,
    targetSize: Dp,
    visualSize: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    tint: Color = TuneColors.Text,
    tag: String = ""
) {
    val previewDescription = description + stringResource(R.string.static_control)
    Box(
        modifier.size(targetSize).testTag(tag).semantics {
            role = Role.Button
            contentDescription = previewDescription
            disabled()
        }, contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(if (visualSize.value > 0) visualSize else iconSize).background(background, CircleShape), contentAlignment = Alignment.Center) {
            TuneIcon(icon, Modifier.requiredSize(iconSize), tint)
        }
    }
}
