package com.daviddokakis.karoointerval

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// Small view builders shared by the Workouts and Account pages. Colours come from resources,
// so they follow light/dark mode (values/ and values-night/).

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.text(value: String, sp: Float, bold: Boolean = false, secondary: Boolean = false) =
    TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

fun Context.button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
    text = label
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER
    setTextColor(getColor(if (primary) R.color.skip_fg else R.color.text))
    setBackgroundResource(if (primary) R.drawable.bg_btn_skip else R.drawable.bg_btn_pause)
    setPadding(dp(14), dp(10), dp(14), dp(10))
    minHeight = dp(44)
    isClickable = true
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
}

/** Icon-only button: no background, 48dp touch area. */
fun Context.iconButton(icon: Int, description: String, onClick: () -> Unit) = ImageView(this).apply {
    setImageResource(icon)
    contentDescription = description
    scaleType = ImageView.ScaleType.CENTER
    isClickable = true
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
}

fun Context.row(vararg views: View) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEach { addView(it) }
}

fun Context.edit(value: String, type: Int) = EditText(this).apply {
    // Single line first: setting it after inputType resets the input type and would show a
    // password field's text in plain view.
    isSingleLine = true
    inputType = type
    if (type and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
        transformationMethod = PasswordTransformationMethod.getInstance()
    }
    setText(value)
    setTextColor(getColor(R.color.text))
    setHintTextColor(getColor(R.color.text_secondary))
    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
}

// Settings-page pieces, shared by Settings and the first-run introduction.

/** Icon + title, like Karoo's "In-Ride Appearance" heading. */
fun Context.settingsHeading(iconRes: Int, title: String) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, dp(14), 0, dp(10))
    addView(settingsIcon(iconRes))
    addView(settingsRowTitle(title))
}

fun Context.settingsIcon(res: Int) = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(getColor(R.color.settings_title))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
}

fun Context.settingsRowTitle(value: String) = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    setTextColor(getColor(R.color.settings_title))
}

fun Context.settingsHelp(value: String) = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setTextColor(getColor(R.color.settings_help))
    setPadding(0, dp(10), 0, dp(14))
}

fun Context.settingsDivider() = View(this).apply {
    setBackgroundColor(getColor(R.color.settings_divider))
    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
}

/** Karoo's segmented control: a pale pill track, the selected option as a blue-grey pill. */
fun <T> Context.segmented(options: List<Pair<Int, T>>, selected: T, onPick: (T) -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    setBackgroundResource(R.drawable.bg_segment_track)
    setPadding(dp(2), dp(2), dp(2), dp(2))
    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(42))
    options.forEach { (label, value) ->
        addView(
            TextView(context).apply {
                text = getString(label)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(getColor(if (value == selected) R.color.segment_text_selected else R.color.segment_text))
                if (value == selected) setBackgroundResource(R.drawable.bg_segment_selected)
                contentDescription = getString(label)
                isSelected = value == selected
                isClickable = true
                setOnClickListener { onPick(value) }
            },
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f),
        )
    }
}
