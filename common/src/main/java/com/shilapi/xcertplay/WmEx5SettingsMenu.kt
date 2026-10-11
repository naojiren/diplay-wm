package com.shilapi.xcertplay

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.roundToInt

/** Enlarge a newly built menu through layout, so visible controls and touch bounds agree. */
internal object WmEx5SettingsMenu {
    private const val SCALE = 1.3f
    fun enlarge(panel: ViewGroup) {
        // The enclosing panel must remain bounded by the current window width.
        for (index in 0 until panel.childCount) enlargeView(panel.getChildAt(index))
    }

    private fun enlargeView(view: View) {
        fun size(value: Int): Int = if (value > 0) (value * SCALE).roundToInt() else value
        view.minimumWidth = size(view.minimumWidth)
        view.minimumHeight = size(view.minimumHeight)
        view.setPadding(size(view.paddingLeft), size(view.paddingTop), size(view.paddingRight), size(view.paddingBottom))
        view.layoutParams?.let { params ->
            params.width = size(params.width)
            params.height = size(params.height)
            if (params is ViewGroup.MarginLayoutParams) {
                val relative = params.isMarginRelative
                val start = params.marginStart
                val end = params.marginEnd
                params.setMargins(size(params.leftMargin), size(params.topMargin), size(params.rightMargin), size(params.bottomMargin))
                if (relative) {
                    params.marginStart = size(start)
                    params.marginEnd = size(end)
                }
            }
            view.layoutParams = params
        }
        if (view is TextView) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * SCALE)
            view.minWidth = size(view.minWidth)
            view.minHeight = size(view.minHeight)
            view.compoundDrawablePadding = size(view.compoundDrawablePadding)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) enlargeView(view.getChildAt(index))
        }
    }
}
