package com.vwshell.auto.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.view.View
import com.vwshell.auto.R

/**
 * Переиспользуемая плитка главного экрана в стиле Discover Pro:
 * тёмно-графитовый фон, тонкая серая рамка, белая иконка, подпись, красное подчёркивание.
 */
class VWTile @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val iconView: ImageView
    private val labelView: TextView
    private val underlineView: android.view.View

    var onTileClick: (() -> Unit)? = null
    /** Долгое нажатие (например, смена выбранного диагностического приложения). */
    var onTileLongClick: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_tile, this, true)
        iconView = findViewById(R.id.tileIcon)
        labelView = findViewById(R.id.tileLabel)
        underlineView = findViewById(R.id.tileUnderline)

        isClickable = true
        isFocusable = true
        // Все видимые части плитки передают нажатие контейнеру: на OEM-прошивках
        // касание по ImageView/TextView иногда не всплывает к FrameLayout как ожидается.
        listOf<View>(iconView, labelView, underlineView).forEach { child ->
            child.isClickable = true
            child.isFocusable = false
            child.setOnClickListener { performClick() }
            child.setOnLongClickListener { performLongClick() }
        }
        // OEM-состояние нажатия: лёгкое изменение фона + анимация подчёркивания
        super.setOnClickListener { v ->
            animateUnderline()
            postDelayed({ onTileClick?.invoke() }, 90) // короткая пауза для тактильной обратной связи, без блокировки UI-потока
        }
        super.setOnLongClickListener {
            onTileLongClick?.invoke()
            onTileLongClick != null
        }

        val a = context.obtainStyledAttributes(attrs, R.styleable.VWTile)
        val iconRes = a.getResourceId(R.styleable.VWTile_tileIcon, 0)
        if (iconRes != 0) iconView.setImageResource(iconRes)
        val label = a.getString(R.styleable.VWTile_tileLabel)
        if (label != null) labelView.text = label
        a.recycle()
    }

    override fun performClick(): Boolean {
        val handled = super.performClick()
        return handled || onTileClick != null
    }

    fun setup(iconRes: Int, labelRes: Int) {
        iconView.setImageResource(iconRes)
        labelView.setText(labelRes)
    }

    /**
     * Активная подсветка плитки (красный акцент Volkswagen #E3182D) — например,
     * когда Bluetooth-плитка отражает выбранный внешний источник плеера.
     */
    fun setHighlighted(active: Boolean) {
        // androidx-версия: Resources.getColor(int, Theme) требует API 23+ на вызывающей стороне;
        // на прошивках с заниженным reported-API прямой вызов может кинуть NoSuchMethodError.
        val color = androidx.core.content.ContextCompat.getColor(
            context, if (active) R.color.vw_accent else R.color.vw_icon_tint)
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(color)
        labelView.setTextColor(color)
    }

    /** Красное подчёркивание «вспыхивает» при нажатии — короткий OEM-анимационный отклик. */
    private fun animateUnderline() {
        underlineView.animate().cancel()
        underlineView.alpha = 0.2f
        underlineView.scaleX = 0.6f
        underlineView.animate()
            .alpha(1f).scaleX(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
