package com.meowv.tune.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowv.tune.R
import com.meowv.tune.ui.theme.TuneColors
import com.meowv.tune.ui.theme.TuneSpacing
import com.meowv.tune.ui.theme.TuneTextStyle

@Composable
internal fun MusicScreen(isCar: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("page_music")) {
        val availableHeight = maxHeight
        val artSize = (if (isCar) 246.dp else 223.dp).coerceAtMost(maxWidth - 48.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = if (isCar) 40.dp else 24.dp, vertical = 20.dp)) {
            Text(stringResource(R.string.menu_music), color = TuneColors.Text, fontSize = if (isCar) 28.sp else 27.sp,
                fontWeight = FontWeight.Bold, style = TuneTextStyle, modifier = Modifier.semantics { heading() })
            Column(Modifier.fillMaxWidth().heightIn(min = (availableHeight - 100.dp).coerceAtLeast(artSize + 100.dp)),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(artSize + 36.dp, artSize + 18.dp), contentAlignment = Alignment.TopCenter) {
                    Image(painterResource(if (isCar) R.drawable.music_record_car else R.drawable.music_record),
                        contentDescription = null, modifier = Modifier.size(artSize))
                    BrandLogo(Modifier.align(Alignment.BottomEnd).size(if (isCar) 62.dp else 54.dp))
                }
                Spacer(Modifier.height(TuneSpacing.Section))
                Text(stringResource(R.string.music_coming_soon), color = TuneColors.Text, fontSize = if (isCar) 28.sp else 25.sp,
                    fontWeight = FontWeight.Bold, style = TuneTextStyle)
            }
        }
    }
}
