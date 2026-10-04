package dev.lumen

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.roundToInt

/** Design tokens from assets/tokens.json, the same file contrast.py checks. */
object Ui {
    private lateinit var t: JSONObject
    private lateinit var ctx: Context

    fun init(c: Context) {
        if (::t.isInitialized) return
        ctx = c.applicationContext
        t = JSONObject(c.assets.open("tokens.json").bufferedReader().use { it.readText() })
    }

    fun color(role: String) = Color.parseColor(t.getJSONObject("color").getString(role))
    fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()
    fun space(step: Int) = dp(t.getJSONArray("space").getInt(step))
    fun radius(name: String) = dp(t.getJSONObject("radius").getInt(name)).toFloat()
    fun size(name: String) = dp(t.getJSONObject("size").getInt(name))
    val roles: List<String> get() = t.getJSONObject("color").keys().asSequence().toList()

    fun <T : TextView> type(v: T, style: String, role: String = "text"): T = v.apply {
        val s = t.getJSONObject("type").getJSONObject(style)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, s.getInt("size").toFloat())
        lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, s.getInt("line").toFloat(), resources.displayMetrics).roundToInt()
        typeface = Typeface.create(null, s.getInt("weight"), false)
        setTextColor(color(role))
    }

    fun pill(fill: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = radius("pill") }
    fun ripple(content: GradientDrawable?) =
        RippleDrawable(ColorStateList.valueOf(color("text") and 0x33FFFFFF), content, pill(Color.WHITE))
}

/** Text button. Disabled = muted colour, not just alpha, so it still passes contrast. */
fun Context.textButton(label: String, onClick: () -> Unit) = Ui.type(TextView(this), "sm").apply {
    text = label
    gravity = Gravity.CENTER
    minHeight = Ui.size("touch")
    minWidth = Ui.size("touch")
    setPadding(Ui.space(4), 0, Ui.space(4), 0)
    setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(Ui.color("text-muted"), Ui.color("text"))))
    background = Ui.ripple(null)
    isClickable = true
    setOnClickListener { onClick() }
}

/** Panel tabs. Selected tab: accent pill. TalkBack reads "selected". */
class Tabs(ctx: Context, names: List<String>, private val onSelect: (Int) -> Unit) : HorizontalScrollView(ctx) {
    private val chips = names.map { name ->
        Ui.type(TextView(ctx), "sm").apply {
            text = name
            gravity = Gravity.CENTER
            minHeight = Ui.dp(36)
            setPadding(Ui.space(4), 0, Ui.space(4), 0)
            isClickable = true
        }
    }

    init {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(ctx).apply {
            setPadding(Ui.space(3), Ui.space(2), Ui.space(3), Ui.space(2))
            chips.forEachIndexed { i, chip ->
                chip.setOnClickListener { select(i) }
                addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.space(2) })
            }
        })
        select(0)
    }

    fun select(i: Int) {
        chips.forEachIndexed { j, c ->
            c.isSelected = i == j
            c.setTextColor(Ui.color(if (i == j) "on-accent" else "text-muted"))
            c.background = Ui.ripple(if (i == j) Ui.pill(Ui.color("accent")) else null)
        }
        onSelect(i)
    }
}

/** Lightroom-style slider: name left, signed value right, -100..100, tap the name to reset. */
class SliderRow(ctx: Context, name: String, onChange: (Int) -> Unit) : LinearLayout(ctx) {
    private val title = name.replaceFirstChar { it.uppercase() }
    private val value = Ui.type(TextView(ctx), "sm", "text-muted").apply { fontFeatureSettings = "tnum" }
    val bar = SeekBar(ctx).apply {
        min = -100; max = 100; progress = 0
        minHeight = Ui.size("touch")
        thumbTintList = ColorStateList.valueOf(Ui.color("accent"))
        progressDrawable = CenterTrack()
        contentDescription = title
    }

    init {
        orientation = VERTICAL
        setPadding(Ui.space(4), Ui.space(2), Ui.space(4), 0)
        val label = Ui.type(TextView(ctx), "sm").apply {
            text = title
            isClickable = true
            contentDescription = "$title, tap to reset"
            setOnClickListener { bar.progress = 0 }
        }
        addView(LinearLayout(ctx).apply {
            addView(label, LayoutParams(0, -2, 1f))
            addView(value)
        })
        addView(bar)
        fun show(p: Int) {
            value.text = "%+d".format(p)
            value.setTextColor(Ui.color(if (p == 0) "text-muted" else "text"))
            bar.stateDescription = value.text
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { show(p); onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        show(0)
    }
}

/** Slider track: flat line, accent fill from the centre (zero) to the thumb. SeekBar drives it via setLevel. */
class CenterTrack : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = Ui.dp(2).toFloat() }
    override fun draw(c: Canvas) {
        val b = bounds; val y = b.exactCenterY()
        val mid = b.exactCenterX(); val at = b.left + b.width() * level / 10000f
        paint.color = Ui.color("track"); c.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, paint)
        paint.color = Ui.color("accent"); c.drawLine(mid, y, at, y, paint)
    }
    override fun onLevelChange(level: Int) = true.also { invalidateSelf() }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("required override") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Every primitive in every state on one screen: `adb shell am start -n dev.lumen/.MainActivity --ez design true`. */
fun Context.designGallery(): View {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.color("bg"))
        setPadding(Ui.space(4), Ui.space(4), Ui.space(4), Ui.space(6))
    }
    fun heading(s: String) = col.addView(Ui.type(TextView(this), "lg").apply { text = s; setPadding(0, Ui.space(5), 0, Ui.space(2)) })

    heading("Colour roles")
    Ui.roles.forEach { role ->
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply { background = Ui.pill(Ui.color(role)).apply { setStroke(Ui.dp(1), Ui.color("text-muted")) } }, LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28)))
            addView(Ui.type(TextView(context), "sm").apply { text = "  $role" })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.space(1) })
    }
    heading("Type")
    listOf("lg", "base", "sm", "xs").forEach { s -> col.addView(Ui.type(TextView(this), s).apply { text = "$s  Exposure +32" }) }
    heading("Buttons")
    col.addView(LinearLayout(this).apply {
        addView(textButton("Open") {})
        addView(textButton("Export") {})
        addView(textButton("Disabled") {}.apply { isEnabled = false })
    })
    heading("Tabs")
    col.addView(Tabs(this, listOf("Light", "Color", "Effects", "Detail")) {}.apply { select(1) })
    heading("Sliders")
    listOf(-50, 0, 75).forEach { p -> col.addView(SliderRow(this, "exposure") {}.apply { bar.progress = p }) }
    return android.widget.ScrollView(this).apply { setBackgroundColor(Ui.color("bg")); addView(col) }
}
