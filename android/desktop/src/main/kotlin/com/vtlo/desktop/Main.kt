package com.vtlo.desktop

import com.vtlo.desktop.ui.BassScreen
import com.vtlo.desktop.ui.DrumScreen
import com.vtlo.desktop.ui.FxScreen
import com.vtlo.desktop.ui.SamplerScreen
import com.vtlo.desktop.ui.StudioWindow
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * VTLO 3O3 desktop. `--screenshots <dir>` renders the five screens to PNG and
 * exits (used to check the layout without a sound card). `--video <dir>` records the
 * video guide frames and soundtrack (see GuideVideo).
 */
fun main(args: Array<String>) {
    System.setProperty("awt.useSystemAAFontSettings", "on")
    val video = args.indexOf("--video").takeIf { it >= 0 }?.let { File(args.getOrElse(it + 1) { "." }) }
    val shots = args.indexOf("--screenshots").takeIf { it >= 0 }?.let { File(args.getOrElse(it + 1) { "." }) }
    SwingUtilities.invokeLater {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val dataDir = if (video != null) File(video, "data") else if (shots != null) File(shots, "data") else Studio.defaultDataDir()
        val studio = if (video != null) Studio(dataDir, sampleRate = 48000).also { it.props["ui.guideSeen"] = "true" } else Studio(dataDir)
        val window = StudioWindow(studio) { host -> listOf(BassScreen(host), BassScreen(host, 2), DrumScreen(host), DrumScreen(host, 2), SamplerScreen(host), FxScreen(host)) }
        if (video != null) {
            // The video guide: a scripted session on a fresh studio, off the UI thread so frames can be paced in real time.
            Thread({
                runCatching { GuideVideo(window, studio).record(video) }.onFailure { it.printStackTrace() }
                exitProcess(0)
            }, "vtlo-video").start()
            return@invokeLater
        }
        if (shots != null) {
            shots.mkdirs()
            listOf("bass", "bass2", "drums", "drums2", "sampler", "fx").forEachIndexed { i, name ->
                ImageIO.write(window.snapshot(1600, 992, i), "png", File(shots, "desktop-$name.png"))
            }
            ImageIO.write(window.snapshot(1600, 1316, 2, deckOpen = true), "png", File(shots, "desktop-deck-drums.png"))
            ImageIO.write(window.snapshot(1600, 1316, 0, deckOpen = true), "png", File(shots, "desktop-deck-bass.png"))
            listOf(0, 3).forEach { g -> ImageIO.write(window.snapshot(1600, 992, 0, guidePage = g), "png", File(shots, "desktop-guide-$g.png")) }
            exitProcess(0)
        }
        window.show()
    }
}
