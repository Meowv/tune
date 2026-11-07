package com.meowv.tune.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import com.meowv.tune.R
import coil3.compose.AsyncImage
import com.meowv.tune.domain.Station
import com.meowv.tune.ui.theme.TuneColors

@Composable
internal fun TuneIcon(@DrawableRes resource: Int, modifier: Modifier = Modifier, tint: Color = TuneColors.Primary) {
    Icon(painterResource(resource), contentDescription = null, modifier = modifier, tint = tint)
}

@Composable
internal fun BrandLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_brand), contentDescription = null, modifier = modifier)
}

@Composable
internal fun StationBadge(station: Station?, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * .2f)).background(TuneColors.Raised)
            .clearAndSetSemantics { }, contentAlignment = Alignment.Center
    ) {
        if (station?.iconUrl == null) {
            TuneIcon(R.drawable.ic_ui_radio, Modifier.size(size * .55f))
        } else {
            val fallback = painterResource(R.drawable.ic_ui_radio)
            AsyncImage(station.iconUrl, null, Modifier.fillMaxSize(), placeholder = fallback, error = fallback,
                contentScale = ContentScale.Fit)
        }
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
    Box(
        modifier.size(targetSize).testTag(tag).semantics {
            role = Role.Button
            contentDescription = description
            disabled()
        }, contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(if (visualSize.value > 0) visualSize else iconSize).background(background, CircleShape), contentAlignment = Alignment.Center) {
            TuneIcon(icon, Modifier.requiredSize(iconSize), tint)
        }
    }
}
