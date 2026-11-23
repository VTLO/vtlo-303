package com.vtlo.acid303

import android.content.pm.ActivityInfo
import com.vtlo.core.ui.PagerLayout
import com.vtlo.drums.dsp.Kit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The combined app: all five instruments load, five swipeable pages, user-selected orientation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w891dp-h411dp-land")
class StudioTest {
    private fun pager(a: MainActivity): PagerLayout {
        fun find(v: android.view.View): PagerLayout? = when (v) {
            is PagerLayout -> v
            is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(a.window.decorView)!!
    }

    private fun coins(v: android.view.View): List<CoinView> = when (v) {
        is CoinView -> listOf(v)
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { coins(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** The emblem sits at the top right and never takes touches, focus or accessibility attention. */
    private fun checkCoin(a: MainActivity) {
        val coin = coins(a.window.decorView).single()
        val row = coin.parent as android.view.ViewGroup
        assertEquals(coin, row.getChildAt(row.childCount - 1))
        assertTrue(!coin.isClickable && !coin.isFocusable && !coin.isLongClickable)
        assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, coin.importantForAccessibility)
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        assertTrue(!coin.onTouchEvent(down))
        down.recycle()
    }

    @Test(timeout = 120000)
    fun allInstrumentsOnFivePages() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).create()
        val a = ctl.get()
        val app = a.application as StudioApp
        // The second bass line and drum machine are separate instruments that start empty.
        assertEquals(5, app.engine.parts.size)
        assertEquals(0, app.drums2.bank.row(0, 0))
        assertTrue((0 until 16).none { app.bass2.bank.step(0, it) and (1 shl 8) != 0 })
        assertEquals(Kit.TR909, app.drums.kit(0))
        assertTrue(app.drums.bank.row(0, 0) != 0) // House preset kick row
        assertTrue(app.bass.bank.step(0, 0) != 0) // Acid Classic first step

        val pager = pager(a)
        assertEquals(6, pager.childCount)
        assertEquals(0, pager.currentPage)
        pager.setPage(2, animate = false)
        assertEquals(2, pager.currentPage)

        // First run: every sampler pad holds a sound from the bundled kits.
        for (p in 0 until 8) assertTrue(app.sampler.sample(p) != null)
        assertEquals("808 BD", app.sampler.padNames[0])

        // Landscape is the default orientation.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, a.requestedOrientation)
        checkCoin(a)
        ctl.destroy()
    }

    @Test(timeout = 120000)
    @Config(qualifiers = "w411dp-h891dp-port")
    fun portraitBuildsAllPages() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).create()
        assertEquals(6, pager(ctl.get()).childCount)
        checkCoin(ctl.get())
        ctl.destroy()
    }

    private fun guides(v: android.view.View): List<GuideView> = when (v) {
        is GuideView -> listOf(v)
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { guides(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test(timeout = 120000)
    fun frenchGuideShowsOnFirstLaunchOnly() {
        val first = Robolectric.buildActivity(MainActivity::class.java).create()
        val guide = guides(first.get().window.decorView).single()
        assertEquals("Bienvenue dans VTLO 3O3", guide.overlay.current.title)
        // Step through to the end with the main button.
        repeat(guide.overlay.pages.size) { guide.performClick() }
        assertTrue(guides(first.get().window.decorView).isEmpty())
        first.destroy()

        val second = Robolectric.buildActivity(MainActivity::class.java).create()
        assertTrue(guides(second.get().window.decorView).isEmpty())
        second.destroy()
    }

    private fun knobs(v: android.view.View): List<com.vtlo.core.ui.KnobView> = when (v) {
        is com.vtlo.core.ui.KnobView -> listOf(v)
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { knobs(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test(timeout = 120000)
    fun pagesAreGroupedLogicallyAndEndWithTheFxSection() {
        assertEquals(listOf("BASS 1", "BASS 2", "DRUMS 1", "DRUMS 2", "SAMPLER", "FX"), MainActivity.PAGES)
        assertEquals(listOf("B1", "B2", "D1", "D2", "SMP"), MainActivity.PART_NAMES)
        val ctl = Robolectric.buildActivity(MainActivity::class.java).create()
        val app = ctl.get().application as StudioApp
        // The mix switches follow the same order as the pages.
        assertEquals(listOf<Any>(app.bass, app.bass2, app.drums, app.drums2, app.sampler), app.engine.parts.map { it.engine })
        // The FX page holds six effects with four knobs each.
        val fxPage = pager(ctl.get()).getChildAt(5)
        assertEquals(24, knobs(fxPage).size)
        ctl.destroy()
    }

    @Test(timeout = 120000)
    fun effectSettingsAreSavedAndRestored() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).create()
        val app = ctl.get().application as StudioApp
        val flanger = app.fx.effect(com.vtlo.fx.FxId.FLANGER)
        flanger.on = true
        flanger.set(2, 0.77f)
        app.fx.effect(com.vtlo.fx.FxId.BEAT_REPEAT).set(0, 1f) // grid 1/32
        app.save()
        ctl.destroy()

        // Rebuild the engine from the saved preferences, as a restart would.
        val create = StudioApp::class.java.getDeclaredMethod("createEngine", Int::class.javaPrimitiveType)
        create.isAccessible = true
        create.invoke(app, 48000)
        val restored = app.fx.effect(com.vtlo.fx.FxId.FLANGER)
        assertTrue(restored.on)
        assertEquals(0.77f, restored.get(2), 0f)
        assertEquals(1f, app.fx.effect(com.vtlo.fx.FxId.BEAT_REPEAT).get(0), 0f)
        assertTrue(!app.fx.effect(com.vtlo.fx.FxId.CHORUS).on)
    }
}
