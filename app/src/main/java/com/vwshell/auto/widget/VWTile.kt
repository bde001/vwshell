package com.vwshell.auto.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vwshell.auto.R

/**
 * Плитка главного экрана в стиле Discover Pro.
 * Корневой layout (view_tile.xml) — вертикальный LinearLayout:
 * FrameLayout-плитка с градиентом и красной полоской снизу, а подпись tileLabel — СТРОГО ПОД плиткой.
 */
class VWTile @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView: ImageView
    private val labelView: TextView
    private val underlineView: android.view.View
    /** Кликабельная область — сама плитка (FrameLayout), не включая подпись под ней. */
    private val cardView: android.view.View

    /** Применение runtime-палитры (Фича 3): фон плитки, полоска, shadow подписи. */
    fun applyPalette() {
        cardView.background = com.vwshell.auto.core.PaletteManager.tileBackground()
        underlineView.background = com.vwshell.auto.core.PaletteManager.stripeDrawable()
        labelView.setShadowLayer(4f, 0f, 0f, com.vwshell.auto.core.PaletteManager.glowShadowColor())
    }

    var onTileClick: (() -> Unit)? = null
    /** Долгое нажатие (например, смена выбранного диагностического приложения). */
    var onTileLongClick: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_tile, this, true)
        iconView = findViewById(R.id.tileIcon)
        labelView = findViewById(R.id.tileLabel)
        underlineView = findViewById(R.id.tileUnderline)
        cardView = findViewById(R.id.tileCard)

        // Клик/долгое нажатие — на весь item (как требует адаптер), обратная связь — на самой плитке.
        isClickable = true
        isFocusable = true
        setOnClickListener {
            animateUnderline()
            postDelayed({ onTileClick?.invoke() }, 90) // короткая пауза для тактильной обратной связи, без блокировки UI-потока
        }
        setOnLongClickListener {
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

    fun setup(iconRes: Int, labelRes: Int) {
        iconView.setImageResource(iconRes)
        labelView.setText(labelRes)
    }

    /**
     * Активная подсветка плитки (красный акцент #FF1A1A через vw_accent) — например,
     * когда плитка отражает выбранный внешний источник плеера.
     */
    fun setHighlighted(active: Boolean) {
        // Фича 3: подсветка следует выбранной палитре (PaletteManager.accent()),
        // а не хардкод-ресурсу. androidx ColorStateList — безопасен для заниженных HU API.
        val color = if (active) com.vwshell.auto.core.PaletteManager.accent()
                    else androidx.core.content.ContextCompat.getColor(context, R.color.vw_icon_tint)
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
