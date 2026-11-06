package com.meowv.tune.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.meowv.tune.R

/** V2.1 静态界面。仅导航和分类选中态在内存中切换，业务控件显示占位状态。 */
class TuneUi(private val context: Context, initialPage: Int) {
    var selectedPage = initialPage.coerceIn(0, 2)
        private set
    private var car = false
    private var wideCar = false
    private var lastSize = Pair(0, 0)
    private lateinit var content: FrameLayout
    private val navigation = mutableListOf<LinearLayout>()
    private var pages = emptyList<View>()
    private val density = context.resources.displayMetrics.density
    private val bg = context.getColor(R.color.background)
    private val surface = context.getColor(R.color.surface)
    private val raised = context.getColor(R.color.surface_pressed)
    private val primary = context.getColor(R.color.accent)
    private val textColor = context.getColor(R.color.text_primary)
    private val muted = context.getColor(R.color.text_secondary)
    private val border = context.getColor(R.color.border)
    private val labels = listOf(R.string.menu_radio, R.string.menu_music, R.string.menu_settings)
    private val icons = listOf(R.drawable.ic_ui_radio, R.drawable.ic_ui_music, R.drawable.ic_ui_settings)

    fun render(host: FrameLayout, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || lastSize == Pair(width, height)) return
        lastSize = Pair(width, height)
        val widthDp = width / density
        val heightDp = height / density
        car = widthDp >= 600 && heightDp >= 360 &&
            (widthDp >= 840 && widthDp > heightDp || widthDp / heightDp >= 1.35f)
        wideCar = car && widthDp >= 1000
        navigation.clear()
        host.removeAllViews()
        val shell = column()
        host.addView(shell, FrameLayout.LayoutParams(-1, -1))
        shell.addView(brandHeader(), params(-1, -2))
        shell.addView(divider())
        content = FrameLayout(context)
        if (car) {
            val body = row()
            val rail = column().apply {
                setBackgroundColor(surface)
                setPadding(dp(16), dp(24), dp(16), dp(16))
            }
            rail.addView(text(R.string.nav_caption, 13f, muted).apply {
                setPadding(dp(12), 0, 0, dp(20))
            })
            addNavigation(rail)
            body.addView(rail, params(if (wideCar) 220 else 164, -1))
            body.addView(divider(), params(1, -1))
            body.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
            shell.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            shell.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        shell.addView(divider())
        shell.addView(player(), params(-1, -2))
        if (!car) {
            shell.addView(divider())
            val bottom = row().apply {
                setBackgroundColor(surface)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            addNavigation(bottom)
            shell.addView(bottom, params(-1, -2))
        }
        pages = listOf(radioPage(), musicPage(), settingsPage())
        showPage(selectedPage)
    }

    private fun brandHeader(): View {
        val header = row().apply {
            setPadding(dp(if (car) 28 else 20), dp(12), dp(20), dp(12))
            minimumHeight = dp(if (car) 89 else 73)
            setBackgroundColor(if (car) surface else bg)
        }
        header.addView(icon(R.drawable.ic_brand, tint = null), params(if (car) 56 else 44, if (car) 56 else 44))
        val wording = LinearLayout(context).apply {
            orientation = if (wideCar) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val brand = text(R.string.brand_full, if (car) 27f else 20f, textColor, true).apply {
            setSingleLine(true)
            // 极窄窗口仍保留完整品牌文字，按可用宽度收缩字号。
            setAutoSizeTextTypeUniformWithConfiguration(10, if (car) 27 else 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val brandWidth = if (wideCar) 320 else -1
        wording.addView(brand, params(brandWidth, -2))
        wording.addView(text(R.string.brand_tagline, if (car) 17f else 13f, muted).apply {
            setSingleLine(true)
            setAutoSizeTextTypeUniformWithConfiguration(9, if (car) 17 else 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            setPadding(if (wideCar) dp(24) else 0, if (wideCar) 0 else dp(4), 0, 0)
        }, params(if (wideCar) 280 else -1, -2))
        header.addView(wording, LinearLayout.LayoutParams(0, -2, 1f))
        return header
    }

    private fun addNavigation(parent: LinearLayout) {
        labels.forEachIndexed { index, label ->
            val item = LinearLayout(context).apply {
                orientation = if (car) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = if (car) Gravity.CENTER_VERTICAL else Gravity.CENTER
                minimumHeight = dp(if (car) 68 else 60)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = interactiveBackground()
                isFocusable = true
                contentDescription = context.getString(label)
                setOnClickListener { showPage(index) }
            }
            item.addView(icon(icons[index]), params(if (car) 28 else 24, if (car) 28 else 24))
            item.addView(text(label, if (car) 20f else 13f, muted).apply {
                setPadding(if (car) dp(16) else 0, if (car) 0 else dp(4), 0, 0)
                setSingleLine(true)
            })
            val layout = if (car) params(-1, -2).apply { bottomMargin = dp(16) }
                else LinearLayout.LayoutParams(0, -2, 1f)
            parent.addView(item, layout)
            navigation.add(item)
        }
    }

    private fun showPage(page: Int) {
        selectedPage = page
        navigation.forEachIndexed { index, view ->
            view.isSelected = index == page
            val color = if (index == page) primary else muted
            (view.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
            (view.getChildAt(1) as TextView).setTextColor(color)
        }
        content.removeAllViews()
        content.addView(pages[page], FrameLayout.LayoutParams(-1, -1))
    }

    private fun radioPage(): View {
        val list = pageColumn()
        val heading = row()
        val titles = column()
        titles.addView(title(if (car) R.string.radio_car_title else R.string.menu_radio))
        titles.addView(subtitle(R.string.radio_subtitle))
        heading.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        if (wideCar) heading.addView(radioTabs(), params(344, -2))
        list.addView(heading)
        gap(list, 20)
        val searchAndFilters = LinearLayout(context).apply {
            orientation = if (wideCar) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val search = row().apply {
            minimumHeight = dp(if (car) 56 else 50)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = shape(surface, 15, border)
            contentDescription = context.getString(R.string.search_preview_description)
        }
        search.addView(icon(R.drawable.ic_ui_search, muted), params(24, 24))
        search.addView(text(R.string.search_hint, if (car) 18f else 16f, muted).apply {
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        searchAndFilters.addView(search, if (wideCar) LinearLayout.LayoutParams(0, -2, 1f) else params(-1, -2))
        if (!wideCar) {
            list.addView(searchAndFilters)
            gap(list, 16)
            list.addView(radioTabs(), params(-1, -2))
            gap(list, 12)
            list.addView(filters(), params(-1, -2))
        } else {
            searchAndFilters.addView(filters(), params(-2, -2).apply { marginStart = dp(16) })
            list.addView(searchAndFilters)
        }
        gap(list, 20)
        val section = row()
        section.addView(text(R.string.radio_section, if (car) 19f else 17f, textColor, true), LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(text(R.string.preview_badge, 12f, muted))
        list.addView(section)
        gap(list, 12)
        val samples = listOf(
            Station("CN", R.string.station_city, R.string.station_city_meta, "#6053A1"),
            Station("JZ", R.string.station_jazz, R.string.station_jazz_meta, "#8660B0"),
            Station("WB", R.string.station_wave, R.string.station_wave_meta, "#3F6B88"),
            Station("FM", R.string.station_pop, R.string.station_city_meta, "#8A5978")
        )
        samples.forEach { station ->
            list.addView(stationRow(station), params(-1, -2))
            gap(list, 8)
        }
        list.addView(text(R.string.radio_preview_note, 12f, muted).apply { setPadding(0, dp(8), 0, 0) })
        return scroll(list)
    }

    private fun radioTabs(): View {
        val tabs = row().apply {
            background = shape(surface, 15)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf(R.string.tab_popular, R.string.tab_updated, R.string.tab_random).forEachIndexed { index, label ->
            val tab = text(label, if (car) 14f else 13f, muted, index == 0).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(if (car) 56 else 48)
                background = interactiveBackground()
                isSelected = index == 0
                if (index == 0) setTextColor(primary)
                isFocusable = true
                setOnClickListener {
                    for (i in 0 until tabs.childCount) {
                        val sibling = tabs.getChildAt(i) as TextView
                        sibling.isSelected = sibling === this
                        sibling.setTextColor(if (sibling.isSelected) primary else muted)
                    }
                }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return tabs
    }

    private fun filters(): View {
        val chips = row()
        listOf(R.string.filter_country, R.string.filter_language, R.string.filter_tag).forEach { label ->
            val chip = row().apply {
                background = shape(surface, 12, border)
                minimumHeight = dp(if (car) 56 else 48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            chip.addView(text(label, if (car) 16f else 13f, muted))
            chip.addView(icon(R.drawable.ic_ui_chevron_down), params(18, 18).apply { marginStart = dp(12) })
            chips.addView(chip, params(-2, -2).apply { marginEnd = dp(8) })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
    }

    private data class Station(val initials: String, val name: Int, val metadata: Int, val color: String)

    private fun stationRow(station: Station): View {
        val item = row().apply {
            background = shape(surface, 15)
            minimumHeight = dp(if (car) 76 else 80)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        item.addView(TextView(context).apply {
            text = station.initials
            textSize = if (car) 19f else 16f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = shape(Color.parseColor(station.color), 14)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, params(50, 50))
        val words = column().apply { setPadding(dp(14), 0, dp(4), 0) }
        words.addView(text(station.name, if (car) 18f else 15f, textColor, true))
        words.addView(text(station.metadata, if (car) 13f else 11f, muted).apply { setPadding(0, dp(6), 0, 0) })
        item.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        item.addView(playerControl(R.drawable.ic_ui_play, R.string.preview_play, false), params(if (car) 56 else 48, if (car) 56 else 48))
        return item
    }

    private fun musicPage(): View {
        val page = pageColumn()
        page.addView(title(R.string.menu_music))
        page.addView(subtitle(R.string.music_subtitle))
        val center = column().apply { gravity = Gravity.CENTER }
        val art = FrameLayout(context)
        art.addView(ImageView(context).apply { setImageResource(R.drawable.music_record) }, FrameLayout.LayoutParams(-1, -1))
        art.addView(icon(R.drawable.ic_brand, tint = null), FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END))
        center.addView(art, params(210, 210))
        gap(center, 24)
        center.addView(text(R.string.music_coming_soon, if (car) 28f else 25f, textColor, true).apply { gravity = Gravity.CENTER })
        gap(center, 12)
        center.addView(text(R.string.music_description, if (car) 16f else 14f, muted).apply { gravity = Gravity.CENTER })
        gap(center, 20)
        center.addView(text(R.string.music_stage, 13f, primary).apply {
            gravity = Gravity.CENTER
            background = shape(surface, 24, border)
            setPadding(dp(24), dp(12), dp(24), dp(12))
        })
        page.addView(center, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(24); bottomMargin = dp(24) })
        // fillViewport 保证长屏居中，低高窗口则让内容完整滚动。
        return scroll(page)
    }

    private fun settingsPage(): View {
        val page = pageColumn()
        page.addView(title(R.string.menu_settings))
        page.addView(subtitle(R.string.settings_subtitle))
        gap(page, 24)
        sectionLabel(page, R.string.settings_playback)
        val playback = settingsGroup()
        playback.addView(settingRow(R.drawable.ic_ui_play, R.string.setting_autoplay, R.string.setting_autoplay_description, checked = false))
        playback.addView(divider())
        playback.addView(settingRow(R.drawable.ic_ui_wifi, R.string.setting_wifi, R.string.setting_wifi_description, checked = false))
        playback.addView(divider())
        playback.addView(settingRow(R.drawable.ic_ui_info, R.string.setting_data, R.string.setting_data_description, checked = true))
        page.addView(playback)
        gap(page, 24)
        sectionLabel(page, R.string.settings_interface)
        val appearance = settingsGroup()
        appearance.addView(settingRow(R.drawable.ic_ui_moon, R.string.setting_theme, R.string.setting_theme_description, value = R.string.theme_dark))
        appearance.addView(divider())
        appearance.addView(settingRow(R.drawable.ic_ui_layout, R.string.setting_layout, R.string.setting_layout_description, value = R.string.layout_auto))
        page.addView(appearance)
        gap(page, 24)
        sectionLabel(page, R.string.settings_about)
        val about = settingsGroup()
        about.addView(settingRow(R.drawable.ic_ui_refresh, R.string.setting_cache, R.string.setting_cache_description, value = R.string.preview_badge))
        about.addView(divider())
        about.addView(settingRow(R.drawable.ic_ui_info, R.string.setting_about, R.string.setting_about_description, value = R.string.design_version))
        page.addView(about)
        gap(page, 16)
        page.addView(text(R.string.settings_preview_note, 12f, muted))
        return scroll(page)
    }

    private fun settingRow(icon: Int, title: Int, description: Int, checked: Boolean? = null, value: Int? = null): View {
        val item = row().apply {
            minimumHeight = dp(if (car) 72 else 76)
            setPadding(0, dp(12), 0, dp(12))
        }
        item.addView(icon(icon).apply {
            background = shape(raised, 12)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, params(40, 40))
        val words = column().apply { setPadding(dp(12), 0, dp(8), 0) }
        words.addView(text(title, if (car) 18f else 15f, textColor, true))
        words.addView(text(description, if (car) 13f else 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
        item.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        if (checked != null) {
            item.addView(Switch(context).apply {
                isChecked = checked
                isEnabled = false
                thumbTintList = ColorStateList.valueOf(if (checked) textColor else muted)
                trackTintList = ColorStateList.valueOf(if (checked) Color.parseColor("#8B5CF6") else border)
                minimumWidth = dp(if (car) 56 else 48)
                minimumHeight = dp(if (car) 56 else 48)
                contentDescription = context.getString(title) + context.getString(R.string.static_control)
            }, params(-2, -2))
        } else if (value != null) {
            item.addView(text(value, if (car) 14f else 12f, muted))
            item.addView(icon(R.drawable.ic_ui_chevron_right, muted), params(20, 24))
        }
        return item
    }

    private fun player(): View {
        val player = row().apply {
            setBackgroundColor(surface)
            minimumHeight = dp(if (car) 89 else 84)
            setPadding(dp(if (car) 24 else 12), dp(12), dp(12), dp(12))
        }
        player.addView(icon(R.drawable.ic_ui_radio).apply {
            background = shape(raised, 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, params(if (car) 56 else 44, if (car) 56 else 44))
        val info = column().apply { setPadding(dp(12), 0, dp(4), 0) }
        info.addView(text(R.string.player_idle, if (car) 19f else 14f, textColor, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        info.addView(text(R.string.player_idle_description, if (car) 13f else 11f, muted).apply {
            setPadding(0, dp(6), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        player.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        player.addView(playerControl(R.drawable.ic_ui_previous, R.string.player_previous), params(if (car) 64 else 48, if (car) 64 else 48))
        player.addView(playerControl(R.drawable.ic_ui_play, R.string.player_play, true), params(if (car) 64 else 48, if (car) 64 else 48))
        player.addView(playerControl(R.drawable.ic_ui_next, R.string.player_next), params(if (car) 64 else 48, if (car) 64 else 48))
        if (car) player.addView(playerControl(R.drawable.ic_ui_volume, R.string.player_volume), params(64, 64))
        return player
    }

    private fun playerControl(resource: Int, description: Int, prominent: Boolean = false): ImageView = icon(resource, if (prominent) bg else muted).apply {
        setPadding(dp(12), dp(12), dp(12), dp(12))
        if (prominent) background = shape(primary, 40)
        alpha = 0.5f
        isEnabled = false
        contentDescription = context.getString(description) + context.getString(R.string.static_control)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun settingsGroup() = column().apply {
        background = shape(surface, 17)
        setPadding(dp(12), 0, dp(12), 0)
    }

    private fun sectionLabel(parent: LinearLayout, label: Int) {
        parent.addView(text(label, if (car) 19f else 17f, primary, true))
        gap(parent, 12)
    }

    private fun pageColumn() = column().apply {
        setPadding(dp(if (car) 32 else 24), dp(24), dp(if (car) 32 else 24), dp(24))
    }

    private fun scroll(child: View) = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
        addView(child, ViewGroup.LayoutParams(-1, -2))
    }

    private fun title(label: Int) = text(label, if (car) 28f else 27f, textColor, true).apply {
        isAccessibilityHeading = true
    }

    private fun subtitle(label: Int) = text(label, if (car) 15f else 14f, muted).apply { setPadding(0, dp(8), 0, 0) }

    private fun text(label: Int, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setText(label)
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun icon(resource: Int, tint: Int? = primary) = ImageView(context).apply {
        setImageResource(resource)
        if (tint != null) imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun divider() = View(context).apply {
        setBackgroundColor(border)
        layoutParams = params(-1, 1)
    }

    private fun gap(parent: LinearLayout, height: Int) { parent.addView(View(context), params(1, height)) }
    private fun params(width: Int, height: Int) = LinearLayout.LayoutParams(if (width < 0) width else dp(width), if (height < 0) height else dp(height))
    private fun dp(value: Int) = (value * density + 0.5f).toInt()

    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun interactiveBackground(): RippleDrawable {
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(raised, 16, primary))
            addState(intArrayOf(android.R.attr.state_selected), shape(raised, 16))
            addState(intArrayOf(), shape(Color.TRANSPARENT, 16))
        }
        return RippleDrawable(ColorStateList.valueOf(border), states, shape(Color.WHITE, 16))
    }
}
