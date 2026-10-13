package com.galaxytvstick.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import androidx.core.graphics.PathParser
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sin

/**
 * Machin desine yo (pa imoji): machin, machin kous, otobis, kamyon, lapolis, anbilans.
 * Kò ki sote, wou k ap vire, liy vitès dèyè, limyè lapolis/anbilans k ap kliyote.
 * Nan panel la, runner = "<kalite>:RRGGBB" (egz: "bus:FFC107"). Menm SPEC ak public/galaxy/fx.js.
 * Yo tout gade agoch (menm jan ak imoji machin yo), konsa règ "miwa" a menm.
 */
object CarFx {
    const val VH = 44f

    class Part(val fill: Int?, val alpha: Int, val path: Path)
    class Spec(val w: Float, val wheels: FloatArray, val parts: List<Part>, val blink: List<Path>) {
        /** Lajè / wotè desen an (ak liy vitès yo). */
        val ratio get() = (w + 14f) / VH
    }
    class Vehicle(val spec: Spec, val color: Int) { val ratio get() = spec.ratio }

    private const val SPEC = """{"car":{"w":108,"wheels":[26,86],"parts":[["B",1,"M8 31C4 31 3 28 3.5 25L5 21C6 18.5 9 17.5 13 17L30 15L40 6C42 4.5 44 4 47 4L72 4C75 4 77 5 79 7L88 15L101 16.5C105 17 107 19 107 23L107 28C107 30 106 31 103 31Z"],["#BFE6FF",0.9,"M33 15L42 7.5L55 7.5L55 15ZM59 15L59 7.5L72 7.5C74 7.5 75 8 76 9L82 15Z"],["#FFE066",1,"M4.5 21H8.5V24H3.5Z"],["#FF3B3B",1,"M104 19H107V23H104Z"],["#FFFFFF",0.25,"M8 21.5H104V22.5H8Z"],["#000000",0.25,"M56.5 16H57.5V29H56.5Z"]]},"race":{"w":111,"wheels":[26,88],"parts":[["B",1,"M6 31C3 31 2 29 2.5 27L4 24C5 22 8 21 12 20.5L34 18L46 11C48 10 50 9.5 53 9.5L70 9.5C74 9.5 77 11 80 13L92 18L104 17L107 14L111 14L111 28C111 30 110 31 107 31Z"],["#BFE6FF",0.9,"M40 18L49 12.5L60 12.5L60 18ZM63 18L63 12.5L70 12.5C73 12.5 75 13.5 77 15L80 18Z"],["#FFFFFF",0.85,"M5 24H108V26H5Z"],["#FFE066",1,"M3.5 24.5H8.5V27H3.5Z"],["#FF3B3B",1,"M107 19H111V23H107Z"]]},"bus":{"w":141,"wheels":[28,116],"parts":[["B",1,"M6 31C3 31 2 29 2 27L2 6C2 3 4 1 7 1L136 1C139 1 140 3 140 6L140 28C140 30 139 31 137 31Z"],["#BFE6FF",0.9,"M4 4H15V17H4ZM32 4H45V15H32ZM47 4H60V15H47ZM62 4H75V15H62ZM77 4H90V15H77ZM92 4H105V15H92ZM107 4H120V15H107ZM122 4H135V15H122Z"],["#000000",0.28,"M18 4H28V29H18Z"],["#FFFFFF",0.6,"M2 19H140V21.5H2Z"],["#FFE066",1,"M2 23H6V26H2Z"],["#FF3B3B",1,"M137 20H140V25H137Z"]]},"truck":{"w":140,"wheels":[20,100,122],"parts":[["#ECEFF1",1,"M35 2L136 2C138 2 139 3 139 5L139 29C139 30 138 31 137 31L35 31Z"],["B",1,"M7 31C4 31 3 29 3 27L3 18C3 16 4 14 6 13L10 7C11 5 13 4 16 4L30 4C32 4 33 5 33 7L33 31Z"],["B",1,"M35 22H139V25H35Z"],["#BFE6FF",0.9,"M8 14L12 7.5L28 7.5L28 14Z"],["#FFE066",1,"M3 21H7V24H3Z"],["#FF3B3B",1,"M136 24H139V29H136Z"]]},"police":{"w":108,"wheels":[26,86],"parts":[["B",1,"M8 31C4 31 3 28 3.5 25L5 21C6 18.5 9 17.5 13 17L30 15L40 6C42 4.5 44 4 47 4L72 4C75 4 77 5 79 7L88 15L101 16.5C105 17 107 19 107 23L107 28C107 30 106 31 103 31Z"],["#FFFFFF",0.95,"M30 21H84V30H30Z"],["#BFE6FF",0.9,"M33 15L42 7.5L55 7.5L55 15ZM59 15L59 7.5L72 7.5C74 7.5 75 8 76 9L82 15Z"],["#111111",1,"M48 3H64V4.5H48Z"],["#FFE066",1,"M4.5 21H8.5V24H3.5Z"],["#FF3B3B",1,"M104 19H107V23H104Z"],["#FFFFFF",0.25,"M8 21.5H104V22.5H8Z"],["#000000",0.25,"M56.5 16H57.5V29H56.5Z"]],"blink":["M49 0H56V3.5H49Z","M56 0H63V3.5H56Z"]},"ambulance":{"w":108,"wheels":[24,88],"parts":[["B",1,"M7 31C4 31 3 29 3 27L3 19C3 17 4 15 6 14L14 6C15 4.5 17 4 20 4L104 4C106 4 107 5 107 7L107 28C107 30 106 31 104 31Z"],["#BFE6FF",0.9,"M8 15L15 8L28 8L28 15Z"],["#E53935",1,"M3 19H107V22H3Z"],["#E53935",1,"M62 7h5v4h4v5h-4v4h-5v-4h-4v-5h4Z"],["#FFE066",1,"M3 23H7V26H3Z"],["#FF3B3B",1,"M104 20H107V25H104Z"]],"blink":["M28 0.5H34V4H28Z","M34 0.5H40V4H34Z"]}}"""

    private val specs: Map<String, Spec> by lazy {
        val o = JSONObject(SPEC)
        o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k)
            val ps = v.getJSONArray("parts")
            val wh = v.getJSONArray("wheels")
            Spec(
                v.getDouble("w").toFloat(),
                FloatArray(wh.length()) { wh.getDouble(it).toFloat() },
                (0 until ps.length()).map { i ->
                    val a = ps.getJSONArray(i)
                    val f = a.getString(0)
                    Part(if (f == "B") null else Color.parseColor(f), (a.getDouble(1) * 255).toInt(), PathParser.createPathFromPathData(a.getString(2)))
                },
                v.optJSONArray("blink")?.let { b -> (0 until b.length()).map { PathParser.createPathFromPathData(b.getString(it)) } } ?: emptyList()
            )
        }
    }

    private val RE = Regex("^(car|race|bus|truck|police|ambulance):#?([0-9a-fA-F]{6})$", RegexOption.IGNORE_CASE)

    /** Machin desine a si tèks la se "<kalite>:RRGGBB", sinon null (se yon imoji). */
    fun parse(s: String?): Vehicle? {
        val m = RE.matchEntire(s?.trim() ?: return null) ?: return null
        return runCatching { Vehicle(specs.getValue(m.groupValues[1].lowercase()), Color.parseColor("#" + m.groupValues[2])) }.getOrNull()
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** Desine machin nan nan yon bwat (h × h·ratio) ki kòmanse nan (0,0) sou canvas la. */
    fun draw(canvas: Canvas, h: Float, v: Vehicle, t: Long = SystemClock.uptimeMillis()) {
        val s = v.spec
        val k = h / VH
        canvas.save()
        canvas.scale(k, k)
        p.style = Paint.Style.FILL; p.alpha = 255
        // Lonbraj
        p.color = 0x59000000; rect.set(6f, 38.5f, s.w - 6f, 43.5f); canvas.drawOval(rect, p)
        // Liy vitès dèyè l (bò dwat, paske machin nan gade agoch)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 1.6f
        for ((i, y) in floatArrayOf(12f, 20f, 27f).withIndex()) {
            val ph = ((t + i * 110L) % 330L) / 330f
            p.color = Color.argb((200 * (1 - ph)).toInt(), 255, 255, 255)
            val x0 = s.w + 2f + ph * 6f
            canvas.drawLine(x0, y, x0 + 7f - i, y, p)
        }
        // Kò a sote yon ti kras
        canvas.save()
        canvas.translate(0f, -0.7f * (0.5f + 0.5f * sin(2 * PI * (t % 300) / 300.0).toFloat()))
        p.style = Paint.Style.FILL
        for (part in s.parts) {
            p.color = part.fill ?: v.color
            p.alpha = part.alpha
            canvas.drawPath(part.path, p)
        }
        // Limyè lapolis / anbilans: wouj ↔ ble
        val on = (t / 250L) % 2L == 0L
        s.blink.forEachIndexed { i, path ->
            p.color = if ((i % 2 == 0) == on) 0xFFE53935.toInt() else 0xFF1E88E5.toInt()
            canvas.drawPath(path, p)
        }
        canvas.restore()
        // Wou yo k ap vire (sans envès lèzegwi pou yon machin k ap ale agoch)
        val ang = -360f * (t % 400) / 400f
        for (cx in s.wheels) {
            p.style = Paint.Style.FILL; p.alpha = 255
            p.color = 0xFF111111.toInt(); canvas.drawCircle(cx, 31f, 8f, p)
            p.color = 0xFFC9CED6.toInt(); canvas.drawCircle(cx, 31f, 4.6f, p)
            canvas.save(); canvas.rotate(ang, cx, 31f)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f; p.color = 0xFF555555.toInt()
            for (a in 0 until 3) { canvas.drawLine(cx - 4.4f, 31f, cx + 4.4f, 31f, p); canvas.rotate(60f, cx, 31f) }
            canvas.restore()
            p.style = Paint.Style.FILL; p.color = 0xFF555555.toInt(); canvas.drawCircle(cx, 31f, 1.5f, p)
        }
        canvas.restore()
    }
}
