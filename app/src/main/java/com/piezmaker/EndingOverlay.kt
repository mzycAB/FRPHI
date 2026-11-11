package com.piezmaker

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「自己打」结束界面 —— 数据取自 phira-0.8.2/prpr/src/scene/ending.rs，布局按用户点名排：
 *
 *  · 曲名在**左上角**，难度紧跟在曲名**右侧**；
 *  · 「重来」在**右上角**，「继续」在**右下角**；
 *  · 等级/分数那一堆数据整块放到**左侧**（屏幕 2/6 处）；
 *  · **右侧**（屏幕 5/6 处）放选曲页那块灰色长方形（9:16 竖着，图跟着变）。
 *
 * Phira 原版还有成绩上传、RKS、early/late 明细、误差标准差；本地没有账号，
 * 判定只有 PERFECT(蓝打击)/GOOD(黄打击)/MISS 三档，所以 BAD 恒 0。
 */
object EndingOverlay {

    data class Summary(
        val title: String,
        val level: String,
        val score: Int,
        val hit: Int,
        /** 黄打击（GOOD）个数；PERFECT = hit - good */
        val good: Int,
        val total: Int,
        val maxCombo: Int,
        val miss: Int,
        val best: Int,
        val isNewBest: Boolean,
        /** 右侧 9:16 长方形里显示的图（选曲页封面，没有就是灰底） */
        val coverUri: String? = null,
        /** 结算背景 = 打歌时那张高斯模糊背景（用户点名：和自己打界面一致） */
        val blurredBg: android.graphics.Bitmap? = null
    ) {
        val perfect: Int get() = (hit - good).coerceAtLeast(0)
        /** Phira `accuracy()`：(perfect + good × 0.65) / 总数 */
        val accuracy: Double
            get() = if (total == 0) 0.0 else (perfect + good * 0.65) / total
        val fullCombo: Boolean get() = total > 0 && maxCombo >= total
    }

    fun show(
        activity: Activity,
        host: ViewGroup,
        s: Summary,
        onRetry: () -> Unit,
        onProceed: () -> Unit
    ): View {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val white = Color.WHITE
        val dim = 0xAAFFFFFF.toInt()

        val dm = activity.resources.displayMetrics
        val w = (if (host.width > 0) host.width else dm.widthPixels)
        val h = (if (host.height > 0) host.height else dm.heightPixels)

        val root = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xF2181C26.toInt())
            isClickable = true
        }
        // ★ 背景和打歌界面完全一致：同一张模糊图 + 同一层 0x99000000 压暗
        s.blurredBg?.let { bg ->
            root.addView(ImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bg)
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            root.addView(View(activity).apply {
                setBackgroundColor(0x99000000.toInt())
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        // ---------------------------------------------------------------- 左上角：曲名 + 难度
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(activity).apply {
            text = s.title
            setTextColor(white)
            textSize = 19f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (s.level.isNotEmpty()) {
            titleRow.addView(TextView(activity).apply {
                text = s.level
                setTextColor(dim)
                textSize = 14f
                includeFontPadding = false
                setPadding(dp(10), 0, 0, 0)
            })
        }

        // ---------------------------------------------------------------- 右上角：重来 / 右下角：继续
        /**
         * [removeFirst] = 点下去先把这个覆盖层摘掉。
         * 「继续」在选曲页入口要**留着覆盖层**直接 finish（摘了会在退出动画那半秒里露出编辑界面），
         * 所以它传 false，由 Activity 那边的回调自己决定什么时候摘。
         */
        fun actionButton(label: String, removeFirst: Boolean = true, action: () -> Unit): TextView =
            TextView(activity).apply {
                text = label
                setTextColor(white)
                textSize = 15f
                gravity = Gravity.CENTER
                background = roundRect(0x22FFFFFF.toInt(), dp(10), 0x66FFFFFF.toInt())
                isClickable = true
                layoutParams = FrameLayout.LayoutParams(dp(84), dp(40))
                setOnClickListener {
                    if (removeFirst) host.removeView(root)
                    action()
                }
            }


        // ---------------------------------------------------------------- 右侧（5/6 处）：9:16 灰色长方形
        // ★ 严格 9:16（竖着），和选曲页那块同一个比例：先按高度取，宽了再按宽度收，收到之后**重算高度**，
        //   所以无论屏幕多宽多窄，最终都是 9:16 —— 不会出现"被宽度卡成扁条"或"高过头"。
        val centerX = w * 5f / 6f
        // 以 5/6 为中心时，左半边能用的宽度（不能越过屏幕中线）与右半边能用的宽度（留 12dp 边）
        val availHalfW = Math.min(centerX - w / 2f - dp(8), w - centerX - dp(12)).coerceAtLeast(dp(40).toFloat())
        val maxBoxH = h * 0.72f
        var coverW = Math.min(maxBoxH * 9f / 16f, availHalfW * 2f)
        var coverH = coverW * 16f / 9f
        if (coverH > maxBoxH) { coverH = maxBoxH; coverW = coverH * 9f / 16f }
        val coverWi = coverW.toInt().coerceAtLeast(1)
        val coverHi = Math.round(coverW * 16f / 9f).toInt().coerceAtLeast(1)
        val coverView = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFC8CDD6.toInt())
            val u = s.coverUri
            if (!u.isNullOrEmpty()) {
                // 有透明边的图先抠掉透明部分（和选曲页那块长方形一致）
                val bmp = try { ImgFit.loadTrimmed(activity, Uri.parse(u), 1080, 1920) } catch (_: Exception) { null }
                if (bmp != null) {
                    setBackgroundColor(0x00000000)
                    setImageBitmap(bmp)
                }
            }
        }
        // 层次：先放长方形和数据块，最后放曲名与两个按钮 —— 后加的画在上面，
        // 这样即使小屏上哪块算宽了，标题和按钮也不会被盖住。
        root.addView(coverView, FrameLayout.LayoutParams(coverWi, coverHi).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = (centerX - coverWi / 2f).toInt().coerceAtLeast(0)
        })

        // ---------------------------------------------------------------- 左侧（2/6 处）：数据那一堆
        val colW = (w * 0.34f).toInt()
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 评级（Phira 右侧的 8 档图标，这里用大字 + 颜色）
        val (grade, gradeColor) = gradeOf(s)
        val gradeView = TextView(activity).apply {
            text = grade
            setTextColor(gradeColor)
            textSize = 78f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(92)
            )
        }
        card.addView(gradeView)

        // 7 位分数（Phira 用 PGR_FONT 逐位滚动，这里用等宽数字 + 数值滚动动画）
        val scoreView = TextView(activity).apply {
            text = "0000000"
            setTextColor(white)
            textSize = 34f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        card.addView(scoreView)

        val bestView = TextView(activity).apply {
            text = if (s.isNewBest) "新纪录  +${(s.score - s.best).toString().padStart(7, '0')}"
            else "最高  ${s.best.toString().padStart(7, '0')}"
            setTextColor(if (s.isNewBest) 0xFFFFD54F.toInt() else dim)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        card.addView(bestView)

        // 准度 / 命中
        card.addView(TextView(activity).apply {
            text = "准度 ${String.format("%.2f", s.accuracy * 100)}%    |    命中 ${s.hit} / ${s.total}"
            setTextColor(dim)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        })

        // PERFECT / GOOD / BAD / MISS（Phira 的四行计数）
        val counts = listOf("PERFECT" to s.perfect, "GOOD" to s.good, "BAD" to 0, "MISS" to s.miss)
        val countRows = ArrayList<View>(4)
        val rowW = (colW * 0.82f).toInt()
        for ((label, value) in counts) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    rowW, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            row.addView(TextView(activity).apply {
                text = label
                setTextColor(dim)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(activity).apply {
                text = value.toString()
                setTextColor(white)
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.END
            })
            card.addView(row)
            countRows.add(row)
        }

        // 最高连击 + 进度条（Phira 的 max-combo 平行四边形进度条）
        val comboLabel = TextView(activity).apply {
            text = "最高连击  ${s.maxCombo} / ${s.total}"
            setTextColor(dim)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        card.addView(comboLabel)

        val track = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(rowW, dp(8)).apply { topMargin = dp(6) }
            background = roundRect(0x55FFFFFF.toInt(), dp(4))
        }
        val fill = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
            background = roundRect(white, dp(4))
        }
        track.addView(fill)
        card.addView(track)

        root.addView(card, FrameLayout.LayoutParams(colW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = (w / 3f - colW / 2f).toInt().coerceAtLeast(dp(8))
        })

        root.addView(titleRow, FrameLayout.LayoutParams(
            (w * 0.52f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(20)
            topMargin = dp(22)
        })
        root.addView(actionButton("重来") { onRetry() }, FrameLayout.LayoutParams(
            dp(84), dp(40)
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = dp(20)
            topMargin = dp(18)
        })
        root.addView(actionButton("继续", removeFirst = false) { onProceed() }, FrameLayout.LayoutParams(
            dp(84), dp(40)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(20)
            bottomMargin = dp(22)
        })
        host.addView(root)

        // ---------------------------------------------------------------- 进场动画
        card.alpha = 0f
        card.translationX = -dp(28).toFloat()
        card.animate().alpha(1f).translationX(0f).setDuration(340)
            .setInterpolator(DecelerateInterpolator(1.4f)).start()

        gradeView.scaleX = 0.35f
        gradeView.scaleY = 0.35f
        gradeView.alpha = 0f
        gradeView.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(190).setDuration(520).setInterpolator(OvershootInterpolator(1.6f)).start()

        coverView.alpha = 0f
        coverView.animate().alpha(1f).setStartDelay(120).setDuration(420).start()

        ValueAnimator.ofInt(0, s.score).apply {
            duration = 1050
            startDelay = 250
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { scoreView.text = (it.animatedValue as Int).toString().padStart(7, '0') }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    scoreView.text = s.score.toString().padStart(7, '0')
                }
            })
            start()
        }

        bestView.alpha = 0f
        bestView.animate().alpha(1f).setStartDelay(880).setDuration(300).start()
        countRows.forEachIndexed { i, row ->
            row.alpha = 0f
            row.translationX = -dp(20).toFloat()
            row.animate().alpha(1f).translationX(0f)
                .setStartDelay(430L + i * 90L).setDuration(320)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        val targetW = if (s.total == 0) 0 else (rowW * (s.maxCombo.toFloat() / s.total)).toInt()
        ValueAnimator.ofInt(0, targetW).apply {
            duration = 850
            startDelay = 700
            interpolator = DecelerateInterpolator(1.3f)
            addUpdateListener {
                val lp = fill.layoutParams
                lp.width = it.animatedValue as Int
                fill.layoutParams = lp
            }
            start()
        }
        return root
    }

    /** Phira judge.rs::icon_index 的 8 档阈值（本地判定只有命中/漏掉，FC 用 maxCombo 判） */
    private fun gradeOf(s: Summary): Pair<String, Int> = when {
        s.score >= 1_000_000 -> "φ" to 0xFFFFD54F.toInt()
        s.score < 700_000 -> "F" to 0xFF9AA0A6.toInt()
        s.score < 820_000 -> "C" to 0xFFFF8A65.toInt()
        s.score < 880_000 -> "B" to 0xFF81C784.toInt()
        s.score < 920_000 -> "A" to 0xFF64B5F6.toInt()
        s.score < 960_000 -> "S-" to 0xFFBA68C8.toInt()
        s.fullCombo -> "V" to 0xFF4DD0E1.toInt()
        else -> "S" to 0xFFBA68C8.toInt()
    }

    private fun roundRect(fill: Int, r: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = r.toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(1, stroke)
        }
}
