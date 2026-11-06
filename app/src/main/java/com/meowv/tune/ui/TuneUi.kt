package com.meowv.tune.ui

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.meowv.tune.R
import kotlin.math.max
import kotlin.math.min

class TuneUi(private val context: Context, initialPage: Int, initialScope: String = "AUTO") {
    var selectedPage = initialPage.coerceIn(0, 2)
        private set
    var selectedScope = initialScope.takeIf { it in listOf("AUTO", "GLOBAL", "CN", "JP", "US", "GB") } ?: "AUTO"
        private set
    private val density = context.resources.displayMetrics.density
    private val fontScale = context.resources.configuration.fontScale
    private val bg = context.getColor(R.color.background)
    private val surface = context.getColor(R.color.surface)
    private val raised = context.getColor(R.color.surface_pressed)
    private val primary = context.getColor(R.color.accent)
    private val textColor = context.getColor(R.color.text_primary)
    private val muted = context.getColor(R.color.text_secondary)
    private val border = context.getColor(R.color.border)
    private val live = Color.rgb(52, 211, 153)
    private var lastSize = Pair(0, 0)
    private var car = false
    private var windowWidth = 0f
    private var pageWidth = 0f
    private var pageHeight = 0f
    private lateinit var content: FrameLayout
    private var pages = emptyList<View>()
    private val navigation = mutableListOf<FrameLayout>()
    private val labels = listOf(R.string.menu_radio, R.string.menu_music, R.string.menu_settings)
    private val icons = listOf(R.drawable.ic_ui_radio, R.drawable.ic_ui_music, R.drawable.ic_ui_settings)
    private val samples = listOf(
        Station("CN", R.string.station_city, "#54468B"),
        Station("MG", R.string.station_morning, "#664D99"),
        Station("YE", R.string.station_night, "#3A6985"),
        Station("SH", R.string.station_mountain, "#795E93"),
        Station("MT", R.string.station_slow, "#487682"),
        Station("QK", R.string.station_sky, "#5F478D")
    )


    fun render(host: FrameLayout, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0 || lastSize == Pair(widthPx, heightPx)) return
        lastSize = Pair(widthPx, heightPx)
        windowWidth = widthPx / density
        val height = heightPx / density
        car = isCarWindow(windowWidth, height)
        val headerHeight = if (car) 79f else 74f
        val playerHeight = max(if (car) 109f else 86f, 60f * fontScale)
        val navHeight = max(56f, 54f * fontScale)
        val railWidth = if (windowWidth >= 1000) 191f else 150f
        pageWidth = windowWidth - if (car) railWidth else 0f
        pageHeight = height - headerHeight - playerHeight - if (car) 0f else navHeight
        navigation.clear()
        host.removeAllViews()
        val shell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        host.addView(shell, FrameLayout.LayoutParams(-1, -1))
        shell.addView(brandHeader(), LinearLayout.LayoutParams(-1, dp(headerHeight)))
        content = FrameLayout(context)
        if (car) {
            val body = LinearLayout(context)
            body.addView(sideNavigation(railWidth), LinearLayout.LayoutParams(dp(railWidth), -1))
            body.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
            shell.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else shell.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(player(), LinearLayout.LayoutParams(-1, dp(playerHeight)))
        if (!car) shell.addView(bottomNavigation(), LinearLayout.LayoutParams(-1, dp(navHeight)))
        pages = listOf(radioPage(), musicPage(), settingsPage())
        showPage(selectedPage)
    }

    private fun brandHeader(): View = FrameLayout(context).apply {
        setBackgroundColor(if (car) surface else bg)
        val size = if (car) 60f else 46f
        place(this, icon(R.drawable.ic_brand, null), if (car) 30f else 17f, if (car) 10f else 14f, size, size)
        val nameX = if (car) 100f else 70f
        val nameSize = if (car) 30f else min(20f, (windowWidth - 90f) / 16f)
        place(this, text(R.string.brand_full, nameSize, textColor, true).apply { letterSpacing = .04f }, nameX,
            if (car) 23f else 20f, if (car) min(335f, windowWidth - nameX - 20f) else windowWidth - nameX - 16f, 34f)
        line(this, 0f, if (car) 78f else 73f, windowWidth)
    }

    private fun sideNavigation(railWidth: Float): View {
        val rail = FrameLayout(context).apply {
        setBackgroundColor(surface)
        labels.forEachIndexed { index, _ ->
            val item = navigationItem(index, railWidth - 32f)
            place(this, item, 16f, 45f + index * 90f, railWidth - 32f, 69f)
        }
        place(this, View(context).apply { setBackgroundColor(border) }, railWidth - 1f, 0f, 1f, max(pageHeight, 320f))
        }
        return ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(rail, ViewGroup.LayoutParams(-1, dp(max(pageHeight, 320f))))
        }
    }

    private fun bottomNavigation(): View = FrameLayout(context).apply {
        setBackgroundColor(surface)
        line(this, 0f, 0f, windowWidth)
        val slot = windowWidth / 3f
        labels.forEachIndexed { index, _ ->
            place(this, navigationItem(index, slot - 24f), index * slot + 12f, 5f, slot - 24f, 42f)
        }
    }

    private fun navigationItem(index: Int, itemWidth: Float): FrameLayout = FrameLayout(context).apply {
        background = interactiveBackground(16f)
        isFocusable = true
        contentDescription = context.getString(labels[index])
        setOnClickListener { showPage(index) }
        val iconSize = if (car) 30f else 24f
        val groupWidth = if (car) 106f else 78f
        val start = (itemWidth - groupWidth) / 2
        place(this, icon(icons[index]), start, if (car) 19f else 9f, iconSize, iconSize)
        place(this, text(labels[index], if (car) 23f else 14f, muted), start + iconSize + 12f,
            if (car) 14f else 4f, groupWidth - iconSize - 12f, if (car) 40f else 34f)
        navigation.add(this)
    }

    private fun showPage(page: Int) {
        selectedPage = page
        navigation.forEachIndexed { index, item ->
            item.isSelected = index == page
            val color = if (item.isSelected) primary else muted
            (item.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
            (item.getChildAt(1) as TextView).apply {
                setTextColor(color)
                typeface = if (item.isSelected || !car) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        content.removeAllViews()
        content.addView(pages[page], FrameLayout.LayoutParams(-1, -1))
    }

    private fun radioPage(): View {
        val page = FrameLayout(context)
        val inset = if (car) 32f else 20f
        val usable = pageWidth - 2 * inset
        val titleX = if (car) 40f else 22f
        place(page, text(R.string.menu_radio, if (car) 36f else 29f, textColor, true).apply { isAccessibilityHeading = true },
            titleX, if (car) 28f else 18f, pageWidth - titleX * 2, 56f)
        val wide = car && pageWidth >= 850f
        val scopeWidth = if (wide) 317f else usable
        val scopeY = if (wide) 34f else if (car) 96f else 88f
        place(page, scopeSelector(scopeWidth, wide), if (wide) pageWidth - inset - scopeWidth else inset,
            scopeY, scopeWidth, if (wide) 67f else 54f)
        val firstY = if (wide) 125f else if (car) 166f else 158f
        val rowHeight = max(if (car) 60f else 72f, 48f * fontScale)
        val gap = if (car) 6f else 4f
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        samples.forEachIndexed { index, station ->
            val playing = index == 0 && selectedScope in listOf("AUTO", "CN", "GLOBAL")
            list.addView(stationRow(station, playing, usable, rowHeight), LinearLayout.LayoutParams(-1, dp(rowHeight)).apply {
                if (index > 0) topMargin = dp(gap)
            })
        }
        if (pageHeight - firstY < rowHeight * 2) {
            val listHeight = samples.size * (rowHeight + gap)
            place(page, list, inset, firstY, usable, listHeight)
            return scroll(page, firstY + listHeight + 12f)
        }
        val listViewport = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }
        place(page, listViewport, inset, firstY, usable, max(60f, pageHeight - firstY - 12f))
        return page
    }

    private fun scopeName(): String = context.getString(when (selectedScope) {
        "GLOBAL" -> R.string.scope_global
        "JP" -> R.string.scope_japan
        "US" -> R.string.scope_us
        "GB" -> R.string.scope_uk
        else -> R.string.scope_china
    })

    private fun scopeSelector(selectorWidth: Float, wide: Boolean): View = FrameLayout(context).apply {
        background = interactiveBackground(15f, surface, border)
        isFocusable = true
        contentDescription = context.getString(R.string.scope_picker_description, scopeName())
        setOnClickListener { showScopePicker() }
        if (wide) {
            place(this, text(R.string.scope_label, 18f, muted), 24f, 0f, 106f, 67f)
            place(this, text(scopeName(), 24f, textColor, true), 137f, 0f, selectorWidth - 190f, 67f)
            place(this, icon(R.drawable.ic_ui_chevron_down), selectorWidth - 48f, 21f, 24f, 24f)
        } else {
            place(this, text(R.string.scope_label, 11f, muted), 14f, 7f, selectorWidth - 120f, 20f)
            place(this, text(scopeName(), 17f, textColor, true), 14f, 26f, selectorWidth - 120f, 25f)
            val button = FrameLayout(context).apply {
                background = shape(raised, 12f)
                place(this, text(R.string.scope_change, 12f, primary, true), 12f, 0f, 52f, 36f)
                place(this, icon(R.drawable.ic_ui_chevron_down), 66f, 10f, 16f, 16f)
            }
            place(this, button, selectorWidth - 98f, 9f, 88f, 36f)
        }
    }

    private fun showScopePicker() {
        val dialog = Dialog(context)
        val dialogWidth = min(if (car) 460f else windowWidth, windowWidth - if (car) 48f else 0f)
        val options = listOf("AUTO" to R.string.scope_auto_china, "GLOBAL" to R.string.scope_global,
            "CN" to R.string.scope_china, "JP" to R.string.scope_japan, "US" to R.string.scope_us, "GB" to R.string.scope_uk)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(surface, 20f)
            setPadding(dp(24f), dp(20f), dp(24f), dp(20f))
        }
        body.addView(text(R.string.scope_label, 22f, textColor, true), LinearLayout.LayoutParams(-1, dp(40f)))
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { index, (key, label) ->
            if (index == 2) rows.addView(text(R.string.scope_other_countries, 13f, muted), LinearLayout.LayoutParams(-1, dp(36f)))
            val item = FrameLayout(context).apply {
                background = interactiveBackground(12f)
                isSelected = selectedScope == key
                isFocusable = true
                contentDescription = context.getString(label)
                place(this, text(label, 17f, if (isSelected) primary else textColor), 16f, 0f, dialogWidth - 116f, 56f)
                if (isSelected) place(this, icon(R.drawable.ic_ui_check), dialogWidth - 92f, 16f, 24f, 24f)
                setOnClickListener {
                    selectedScope = key
                    pages = listOf(radioPage(), pages[1], pages[2])
                    showPage(selectedPage)
                    dialog.dismiss()
                }
            }
            rows.addView(item, LinearLayout.LayoutParams(-1, dp(56f)))
        }
        body.addView(ScrollView(context).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(body)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.55f)
            setGravity(if (car) Gravity.CENTER else Gravity.BOTTOM)
            setLayout(dp(dialogWidth), dp(min(if (car) 490f else 520f, pageHeight + if (car) 0f else 100f)))
        }
        dialog.show()
    }

    private data class Station(val initials: String, val name: Int, val color: String)

    private fun stationRow(station: Station, playing: Boolean, rowWidth: Float, rowHeight: Float): View = FrameLayout(context).apply {
        background = shape(if (playing) raised else surface, if (car) 14f else 13f, if (playing) primary else null)
        val size = 46f
        place(this, badge(station, size), if (car) 14f else 11f, (rowHeight - size) / 2, size, size)
        val wordsX = if (car) 77f else 66f
        val wordsWidth = rowWidth - wordsX - if (car) 88f else 60f
        place(this, text(station.name, if (car) 20f else 16f, textColor, true), wordsX,
            (rowHeight - if (playing) 48f else 32f) / 2, wordsWidth, 32f)
        if (playing) {
            place(this, dot(live), wordsX, rowHeight / 2 + 9f, 7f, 7f)
            place(this, text(R.string.station_live_label, if (car) 14f else 12f, live), wordsX + 14f,
                rowHeight / 2 + 1f, wordsWidth - 14f, 24f)
        }
        val control = displayControl(if (playing) R.drawable.ic_radio_pause else R.drawable.ic_radio_play,
            if (playing) R.string.player_demo_pause else R.string.preview_play, if (car) 26f else 22f, playing).apply {
            background = shape(if (playing) Color.rgb(139, 92, 246) else Color.rgb(34, 45, 73), 30f)
        }
        place(this, control, rowWidth - if (car) 70f else 49f, (rowHeight - if (car) 44f else 34f) / 2,
            if (car) 44f else 34f, if (car) 44f else 34f)
        if (playing) (control as FrameLayout).getChildAt(0).let { (it as ImageView).imageTintList = ColorStateList.valueOf(textColor) }
        contentDescription = context.getString(station.name) + context.getString(R.string.static_control)
    }

    private fun badge(station: Station, size: Float = 50f): View = FrameLayout(context).apply {
        val scale = size / 50f
        background = shape(Color.parseColor(station.color), 10f * scale)
        place(this, text(station.initials, 16f * scale, textColor, true).apply { gravity = Gravity.CENTER }, 0f, 12f * scale, size, 30f * scale)
        place(this, dot(primary), 34f * scale, 4f * scale, 10f * scale, 10f * scale)
        place(this, dot(Color.rgb(211, 200, 255)), 6f * scale, 36f * scale, 6f * scale, 6f * scale)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun musicPage(): View {
        val page = FrameLayout(context)
        pageHeading(page, R.string.menu_music)
        val artSize = if (car) 246f else 223f
        val artY = max(90f, (pageHeight - artSize - 70f) / 2)
        place(page, icon(if (car) R.drawable.music_record_car else R.drawable.music_record, null),
            (pageWidth - artSize) / 2, artY, artSize, artSize)
        place(page, icon(R.drawable.ic_brand, null), pageWidth / 2 + if (car) 83f else 73f,
            artY + artSize - 48f, if (car) 62f else 54f, if (car) 62f else 54f)
        place(page, text(R.string.music_coming_soon, if (car) 28f else 25f, textColor, true).apply { gravity = Gravity.CENTER },
            24f, artY + artSize + 24f, pageWidth - 48f, 44f)
        return scroll(page, artY + artSize + 84f)
    }

    private fun settingsPage(): View {
        val page = FrameLayout(context)
        val inset = if (car) 40f else 24f
        val usable = pageWidth - inset * 2 - if (car) 4f else 0f
        pageHeading(page, R.string.menu_settings)
        val rowHeight = (if (car) 56f else 52f) * max(1f, fontScale)
        var groupY = if (car) 84f else 100f
        sectionLabel(page, R.string.settings_playback, groupY - 30f)
        val playback = group()
        listOf(R.drawable.ic_ui_play to R.string.setting_autoplay, R.drawable.ic_ui_wifi to R.string.setting_wifi,
            R.drawable.ic_ui_info to R.string.setting_data).forEachIndexed { index, (resource, label) ->
            place(playback, settingRow(resource, label, usable - 24f, rowHeight, checked = index == 2),
                12f, index * rowHeight, usable - 24f, rowHeight)
            if (index < 2) line(playback, 16f, (index + 1) * rowHeight, usable - 32f)
        }
        place(page, playback, inset, groupY, usable, rowHeight * 3)
        groupY += rowHeight * 3 + 36f
        sectionLabel(page, R.string.settings_interface, groupY - 30f)
        val appearance = group()
        place(appearance, settingRow(R.drawable.ic_ui_moon, R.string.setting_theme, usable - 24f, rowHeight, value = R.string.theme_dark),
            12f, 0f, usable - 24f, rowHeight)
        line(appearance, 16f, rowHeight, usable - 32f)
        place(appearance, settingRow(R.drawable.ic_ui_layout, R.string.setting_layout, usable - 24f, rowHeight, value = R.string.layout_auto),
            12f, rowHeight, usable - 24f, rowHeight)
        place(page, appearance, inset, groupY, usable, rowHeight * 2)
        groupY += rowHeight * 2 + 36f
        sectionLabel(page, R.string.settings_about, groupY - 30f)
        val about = group()
        place(about, settingRow(R.drawable.ic_ui_refresh, R.string.setting_cache, usable - 24f, rowHeight),
            12f, 0f, usable - 24f, rowHeight)
        line(about, 16f, rowHeight, usable - 32f)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        place(about, text(context.getString(R.string.about_app_version, version), 13f, muted),
            20f, rowHeight, usable - 32f, 32f)
        place(page, about, inset, groupY, usable, rowHeight + 32f)
        return scroll(page, groupY + rowHeight + 48f)
    }

    private fun settingRow(resource: Int, title: Int, rowWidth: Float, height: Float,
                           checked: Boolean? = null, value: Int? = null): View = FrameLayout(context).apply {
        val badge = FrameLayout(context).apply {
            background = shape(raised, 12f)
            place(this, icon(resource), 8f, 8f, 22f, 22f)
        }
        place(this, badge, 0f, (height - 38f) / 2, 38f, 38f)
        val tailWidth = if (value != null) 70f else 56f
        val wordsWidth = rowWidth - 50f - tailWidth
        place(this, text(title, if (car) 17f else 15f, textColor, true), 50f,
            (height - 32f * max(1f, fontScale)) / 2, wordsWidth, 32f * max(1f, fontScale))
        if (checked != null) place(this, toggle(checked, title), rowWidth - 56f, (height - 48f) / 2, 56f, 48f)
        if (value != null) {
            place(this, text(value, if (car) 14f else 13f, muted).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }, rowWidth - 70f, (height - 32f) / 2, 48f, 32f)
            place(this, icon(R.drawable.ic_ui_chevron_right, muted), rowWidth - 16f, (height - 20f) / 2, 16f, 20f)
        }
    }

    private fun toggle(checked: Boolean, title: Int): View = FrameLayout(context).apply {
        val track = FrameLayout(context).apply {
            background = shape(if (checked) Color.rgb(139, 92, 246) else border, 14f)
            place(this, dot(if (checked) textColor else muted), if (checked) 25f else 5f, 4f, 20f, 20f)
        }
        place(this, track, 3f, 10f, 50f, 28f)
        contentDescription = context.getString(title) + context.getString(if (checked) R.string.preview_toggle_on else R.string.preview_toggle_off)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun player(): View = FrameLayout(context).apply {
        setBackgroundColor(if (car) surface else bg)
        if (car) line(this, 0f, 0f, windowWidth)
        val panelWidth = windowWidth - if (car) 0f else 18f
        val h = if (car) 109f else 68f
        val panel = FrameLayout(context).apply {
            if (!car) background = shape(surface, 12f, border)
            val size = if (car) 70f else 42f
            place(this, badge(samples[0], size), if (car) 29f else 8f, (h - size) / 2, size, size)
            val wordsX = if (car) 116f else 58f
            val controlsWidth = if (car) 420f else 146f
            val wordsWidth = max(40f, panelWidth - wordsX - controlsWidth)
            place(this, text(R.string.station_city, if (car && windowWidth >= 1000) 24f else 15f, textColor, true), wordsX,
                if (car) 19f else 10f, wordsWidth, if (car) 40f else 30f)
            place(this, dot(live), wordsX + 1f, if (car) 71f else 45f, if (car) 10f else 7f, if (car) 10f else 7f)
            place(this, text(R.string.station_live_label, if (car) 17f else 12f, muted), wordsX + 20f,
                if (car) 59f else 34f, wordsWidth - 20f, 30f)
            val previousX = if (car) panelWidth - 364f else panelWidth - 164f
            val pauseX = if (car) panelWidth - 290f else panelWidth - 110f
            val nextX = if (car) panelWidth - 207f else panelWidth - 62f
            place(this, displayControl(R.drawable.ic_radio_previous, R.string.player_previous, if (car) 64f else 34f), previousX, (h - 56f) / 2, 56f, 56f)
            val pauseSize = if (car) 72f else 44f
            place(this, displayControl(R.drawable.ic_radio_pause, R.string.player_demo_pause, if (car) 44f else 28f, true), pauseX, (h - pauseSize) / 2, pauseSize, pauseSize)
            place(this, displayControl(R.drawable.ic_radio_next, R.string.player_next, if (car) 64f else 34f), nextX, (h - 56f) / 2, 56f, 56f)
        }
        place(this, panel, if (car) 0f else 9f, if (car) 0f else 8f, panelWidth, h)
    }

    private fun displayControl(resource: Int, description: Int, size: Float, prominent: Boolean = false): View = FrameLayout(context).apply {
        if (prominent) background = shape(primary, 50f)
        addView(icon(resource, if (prominent) Color.rgb(11, 16, 32) else if (resource == R.drawable.ic_radio_play) primary else textColor),
            FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
        contentDescription = context.getString(description) + context.getString(R.string.static_control)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun pageHeading(page: FrameLayout, title: Int) {
        val inset = if (car) 40f else 24f
        place(page, text(title, if (car) 28f else 27f, textColor, true).apply { isAccessibilityHeading = true },
            inset, 14f, pageWidth - inset * 2, 48f)
    }

    private fun sectionLabel(page: FrameLayout, label: Int, y: Float) {
        val inset = if (car) 40f else 24f
        place(page, text(label, 14f, primary, true), inset, y, pageWidth - inset * 2, 30f)
    }

    private fun group() = FrameLayout(context).apply { background = shape(surface, 17f) }
    private fun scroll(page: FrameLayout, minHeight: Float): View = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(page, ViewGroup.LayoutParams(-1, dp(max(pageHeight, minHeight))))
    }

    private fun text(label: Int, size: Float, color: Int, bold: Boolean = false) = text(context.getString(label), size, color, bold)
    private fun text(label: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = label
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun icon(resource: Int, tint: Int? = primary) = ImageView(context).apply {
        setImageResource(resource)
        if (tint != null) imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dot(color: Int) = View(context).apply { background = shape(color, 100f) }
    private fun line(parent: FrameLayout, x: Float, y: Float, windowWidth: Float) {
        place(parent, View(context).apply { setBackgroundColor(border) }, x, y, windowWidth, 1f / density)
    }

    private fun place(parent: FrameLayout, child: View, x: Float, y: Float, windowWidth: Float, height: Float) {
        parent.addView(child, FrameLayout.LayoutParams(dp(max(0f, windowWidth)), dp(max(0f, height))).apply {
            leftMargin = dp(x)
            topMargin = dp(y)
        })
    }

    private fun dp(value: Float) = (value * density + .5f).toInt()
    private fun shape(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * density
        if (stroke != null) setStroke(dp(1f), stroke)
    }

    private fun interactiveBackground(radius: Float, idle: Int = Color.TRANSPARENT, stroke: Int? = null): RippleDrawable {
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(raised, radius, primary))
            addState(intArrayOf(android.R.attr.state_selected), shape(raised, radius))
            addState(intArrayOf(), shape(idle, radius, stroke))
        }
        return RippleDrawable(ColorStateList.valueOf(border), states, shape(Color.WHITE, radius))
    }

    companion object {
        fun isCarWindow(windowWidth: Float, height: Float): Boolean = windowWidth >= 600 && height >= 360 &&
            (windowWidth >= 840 && windowWidth > height || windowWidth / height >= 1.35f)
    }
}
