package com.a340.technicalassistant

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** UI-only selector for the PFR Maintenance/Cockpit Status TYPE field. */
object CockpitTypeSelector {
    fun create(activity: AppCompatActivity, parent: LinearLayout, value: String): EditText {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        parent.addView(TextView(activity).apply {
            text = "TYPE - SELECT FROM TSM"
            setTextColor(Color.parseColor("#6E6E73"))
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(5), 0, 0)
        })

        val field = AutoCompleteTextView(activity).apply {
            setText(value, false)
            hint = "Tap to select: OBSERVATIONS, SD FLAG(S), ..."
            setTextColor(Color.parseColor("#1C1C1E"))
            setHintTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            isSingleLine = true
            threshold = 0
            setPadding(dp(5), dp(3), dp(5), dp(5))
            setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0)
            compoundDrawablePadding = dp(6)
            setAdapter(
                ArrayAdapter(
                    activity,
                    android.R.layout.simple_dropdown_item_1line,
                    CockpitTypeCatalog.values
                )
            )
            setOnClickListener { showDropDown() }
            setOnFocusChangeListener { _: View, hasFocus: Boolean -> if (hasFocus) showDropDown() }
        }
        parent.addView(field, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        parent.addView(TextView(activity).apply {
            text = "Select the exact Type printed in the TSM Fault Symptom List. EWD WARNING(S) uses the ECAM field above. Exact manual typing remains allowed."
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 8.5f
            setPadding(dp(5), 0, dp(5), dp(4))
        })
        return field
    }
}
