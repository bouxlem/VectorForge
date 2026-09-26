package com.example.data.fixtures

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

data class BenchmarkFixture(
    val id: Int,
    val name: String,
    val categoryName: String,
    val description: String,
    val expectedOutcome: String,
    val targetSsim: Double,
    val targetEdgeIou: Double,
    val generateBitmap: () -> Bitmap
)

object BenchmarkFixtures {

    val allFixtures: List<BenchmarkFixture> by lazy {
        listOf(
            BenchmarkFixture(
                id = 1,
                name = "Apex Brand Logo",
                categoryName = "Simple Logo",
                description = "Sharp geometric emblem with 3 flat brand colors and clean angles.",
                expectedOutcome = "Edges must remain razor-sharp; node count under 30; hole preserved.",
                targetSsim = 0.98,
                targetEdgeIou = 0.95,
                generateBitmap = { createSimpleLogo() }
            ),
            BenchmarkFixture(
                id = 2,
                name = "Astra Geometric Star",
                categoryName = "Parametric Star & Holes",
                description = "Concentric 8-point star with circular core hole.",
                expectedOutcome = "Parametric star detector should engage; center hole must be negative space.",
                targetSsim = 0.97,
                targetEdgeIou = 0.94,
                generateBitmap = { createStarFixture() }
            ),
            BenchmarkFixture(
                id = 3,
                name = "Pen Tool UI Icon",
                categoryName = "Black & White Icon",
                description = "Monochrome vector bezier pen glyph with anchor handle.",
                expectedOutcome = "Zero stair-stepping; sharp corner preservation; 100% monochrome palette.",
                targetSsim = 0.98,
                targetEdgeIou = 0.96,
                generateBitmap = { createMonochromeIcon() }
            ),
            BenchmarkFixture(
                id = 4,
                name = "Cyber Shield Badge",
                categoryName = "Multi-Element Logo",
                description = "Shield crest with inner lightning bolt and layered borders.",
                expectedOutcome = "Z-order layer stacking correct; no white micro-gaps at border seams.",
                targetSsim = 0.96,
                targetEdgeIou = 0.93,
                generateBitmap = { createShieldLogo() }
            ),
            BenchmarkFixture(
                id = 5,
                name = "Typographic Logotype",
                categoryName = "Typography & Counters",
                description = "Bold lettering 'VECTOR' with enclosed counter holes in 'O' and 'R'.",
                expectedOutcome = "Letter counter holes ('O', 'R') must stay transparent under fill-rule evenodd.",
                targetSsim = 0.97,
                targetEdgeIou = 0.95,
                generateBitmap = { createTypographyFixture() }
            ),
            BenchmarkFixture(
                id = 6,
                name = "Retro Arcade Mascot",
                categoryName = "Cartoon Character",
                description = "Outlined character with vibrant fills and eye pupils.",
                expectedOutcome = "Outline and fill registration must abut perfectly without drift.",
                targetSsim = 0.95,
                targetEdgeIou = 0.92,
                generateBitmap = { createMascotFixture() }
            ),
            BenchmarkFixture(
                id = 7,
                name = "Precision Hairline Circuit",
                categoryName = "Thin-Line Art (<2px)",
                description = "Fine technical lines testing sub-pixel edge survival.",
                expectedOutcome = "Thin 1.5px lines must not fragment or disappear under RDP optimization.",
                targetSsim = 0.92,
                targetEdgeIou = 0.88,
                generateBitmap = { createThinLineFixture() }
            ),
            BenchmarkFixture(
                id = 8,
                name = "Soft Anti-Aliased Ring",
                categoryName = "Sub-Pixel AA Core Test",
                description = "Smooth circular ring with gradual optical anti-aliasing.",
                expectedOutcome = "Sigmoid sub-pixel refiner must recover true circular radius within 0.1px.",
                targetSsim = 0.98,
                targetEdgeIou = 0.96,
                generateBitmap = { createAntiAliasedRing() }
            )
        )
    }

    private fun createSimpleLogo(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Outer Hexagon
        paint.color = Color.parseColor("#4F46E5") // Indigo
        val path = Path()
        val cx = size / 2f
        val cy = size / 2f
        val r = 200f
        for (i in 0 until 6) {
            val angle = Math.toRadians((i * 60 - 30).toDouble())
            val x = cx + r * cos(angle).toFloat()
            val y = cy + r * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)

        // Inner Triangle
        paint.color = Color.parseColor("#06B6D4") // Cyan
        val tri = Path()
        tri.moveTo(cx, cy - 120f)
        tri.lineTo(cx + 110f, cy + 90f)
        tri.lineTo(cx - 110f, cy + 90f)
        tri.close()
        canvas.drawPath(tri, paint)

        // Center circular hole
        paint.color = Color.parseColor("#FFFFFF")
        canvas.drawCircle(cx, cy + 10f, 45f, paint)

        return bmp
    }

    private fun createStarFixture(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F59E0B") // Amber
            style = Paint.Style.FILL
        }

        val cx = size / 2f
        val cy = size / 2f
        val outerR = 210f
        val innerR = 95f
        val rays = 8
        val path = Path()

        for (i in 0 until (rays * 2)) {
            val a = Math.toRadians((i * (360.0 / (rays * 2)) - 90))
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + r * cos(a).toFloat()
            val y = cy + r * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)

        // Center circular core
        paint.color = Color.parseColor("#1E1B4B")
        canvas.drawCircle(cx, cy, 48f, paint)

        return bmp
    }

    private fun createMonochromeIcon(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        val path = Path()
        path.moveTo(256f, 70f)
        path.lineTo(380f, 320f)
        path.lineTo(290f, 320f)
        path.lineTo(256f, 390f)
        path.lineTo(222f, 320f)
        path.lineTo(132f, 320f)
        path.close()
        canvas.drawPath(path, paint)

        // Pen slit
        paint.color = Color.WHITE
        canvas.drawRect(252f, 240f, 260f, 350f, paint)
        canvas.drawCircle(256f, 230f, 14f, paint)

        return bmp
    }

    private fun createShieldLogo(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Shield Body
        paint.color = Color.parseColor("#0F172A") // Dark Slate
        val shield = Path()
        shield.moveTo(120f, 100f)
        shield.lineTo(392f, 100f)
        shield.quadTo(392f, 320f, 256f, 430f)
        shield.quadTo(120f, 320f, 120f, 100f)
        shield.close()
        canvas.drawPath(shield, paint)

        // Shield Inset
        paint.color = Color.parseColor("#3B82F6") // Blue
        val innerShield = Path()
        innerShield.moveTo(150f, 130f)
        innerShield.lineTo(362f, 130f)
        innerShield.quadTo(362f, 305f, 256f, 395f)
        innerShield.quadTo(150f, 305f, 150f, 130f)
        innerShield.close()
        canvas.drawPath(innerShield, paint)

        // Golden Bolt
        paint.color = Color.parseColor("#FBBF24")
        val bolt = Path()
        bolt.moveTo(270f, 160f)
        bolt.lineTo(210f, 270f)
        bolt.lineTo(260f, 270f)
        bolt.lineTo(240f, 360f)
        bolt.lineTo(310f, 240f)
        bolt.lineTo(265f, 240f)
        bolt.close()
        canvas.drawPath(bolt, paint)

        return bmp
    }

    private fun createTypographyFixture(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 140f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("VECTOR", 256f, 230f, paint)

        paint.textSize = 64f
        paint.color = Color.parseColor("#6366F1")
        canvas.drawText("STUDIO", 256f, 330f, paint)

        return bmp
    }

    private fun createMascotFixture(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Face circle
        paint.color = Color.parseColor("#10B981") // Emerald Green
        canvas.drawCircle(256f, 256f, 180f, paint)

        // Outer Dark Outline
        paint.color = Color.parseColor("#064E3B")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 24f
        canvas.drawCircle(256f, 256f, 180f, paint)

        // Eyes White
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(195f, 220f, 38f, paint)
        canvas.drawCircle(317f, 220f, 38f, paint)

        // Pupils Black
        paint.color = Color.BLACK
        canvas.drawCircle(205f, 225f, 18f, paint)
        canvas.drawCircle(327f, 225f, 18f, paint)

        // Smile
        paint.color = Color.parseColor("#064E3B")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 16f
        val smileRect = RectF(180f, 240f, 332f, 340f)
        canvas.drawArc(smileRect, 20f, 140f, false, paint)

        return bmp
    }

    private fun createThinLineFixture(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#0F172A"))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8") // Sky Blue
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
        }

        // Concentric squares with fine connecting line traces
        for (r in 30..220 step 35) {
            canvas.drawRect(256f - r, 256f - r, 256f + r, 256f + r, paint)
        }
        canvas.drawLine(36f, 36f, 476f, 476f, paint)
        canvas.drawLine(36f, 476f, 476f, 36f, paint)

        return bmp
    }

    private fun createAntiAliasedRing(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EC4899") // Pink
            style = Paint.Style.FILL
        }

        canvas.drawCircle(256f, 256f, 190f, paint)

        paint.color = Color.parseColor("#0F172A")
        canvas.drawCircle(256f, 256f, 110f, paint)

        return bmp
    }
}
