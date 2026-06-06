package app.marlboroadvance.mpvex.ui.player.danmaku

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView

class DanmakuOverlay(
  private val danmakuView: DanmakuView,
  private val currentPositionMs: () -> Long,
  private val playbackSpeed: () -> Double,
  private val isPaused: () -> Boolean,
) {
  private var prepared = false
  private var loadedFile: File? = null
  private var clockAnchorPositionMs = 0L
  private var clockAnchorRealtimeMs = 0L

  fun loadForMedia(
    originalUri: Uri?,
    playablePath: String?,
  ) {
    val danmakuFile = findMatchingDanmakuFile(originalUri, playablePath)
    if (danmakuFile == null) {
      release()
      danmakuView.visibility = View.GONE
      return
    }

    if (loadedFile == danmakuFile && prepared) {
      syncTo(currentPositionMs())
      return
    }

    release()
    loadedFile = danmakuFile
    danmakuView.visibility = View.VISIBLE

    val parser = createParser(danmakuFile) ?: run {
      release()
      return
    }

    val danmakuContext = createDanmakuContext()
    danmakuView.setDrawingThreadType(master.flame.danmaku.controller.IDanmakuView.THREAD_TYPE_LOW_PRIORITY)
    danmakuView.setCallback(
      object : DrawHandler.Callback {
        override fun prepared() {
          prepared = true
          val positionMs = currentPositionMs()
          anchorClock(positionMs)
          danmakuView.start(positionMs)
          if (isPaused()) {
            danmakuView.pause()
          }
        }

        override fun updateTimer(timer: DanmakuTimer?) {
          // Keep DanmakuFlameMaster's clock tied to mpv without calling seekTo()
          // during normal playback. seekTo() rebuilds the running danmaku set and
          // can make scrolling items disappear before they leave the screen.
          timer?.update(smoothPositionMs())
        }

        override fun drawingFinished() = Unit

        override fun danmakuShown(danmaku: BaseDanmaku?) = Unit
      },
    )
    danmakuView.prepare(parser, danmakuContext)
    danmakuView.enableDanmakuDrawingCache(true)
    Log.d(TAG, "Loaded danmaku: ${danmakuFile.absolutePath}")
  }

  fun setPaused(paused: Boolean) {
    if (!prepared) return
    runCatching {
      if (paused) {
        anchorClock(currentPositionMs())
        danmakuView.pause()
      } else {
        anchorClock(currentPositionMs())
        danmakuView.resume()
      }
    }.onFailure { e ->
      Log.e(TAG, "Failed to update danmaku pause state", e)
    }
  }

  fun onPlaybackPositionChanged(positionMs: Long) {
    if (!prepared) return

    val nowMs = SystemClock.elapsedRealtime()
    val predictedPositionMs = smoothPositionMs(nowMs)
    val driftMs = positionMs - predictedPositionMs

    if (abs(driftMs) > SEEK_SYNC_THRESHOLD_MS) {
      syncTo(positionMs)
    } else if (abs(driftMs) > CLOCK_CORRECTION_THRESHOLD_MS) {
      val correctionMs = driftMs.coerceIn(-MAX_CLOCK_CORRECTION_STEP_MS, MAX_CLOCK_CORRECTION_STEP_MS)
      anchorClock(predictedPositionMs + correctionMs, nowMs)
    }
  }

  fun syncTo(positionMs: Long) {
    if (!prepared) return

    runCatching {
      anchorClock(positionMs)
      danmakuView.seekTo(positionMs)
    }.onFailure { e ->
      Log.e(TAG, "Failed to sync danmaku position", e)
    }
  }

  fun release() {
    runCatching {
      if (prepared || loadedFile != null) {
        danmakuView.release()
      }
    }.onFailure { e ->
      Log.e(TAG, "Failed to release danmaku view", e)
    }
    prepared = false
    loadedFile = null
    clockAnchorPositionMs = 0L
    clockAnchorRealtimeMs = 0L
  }

  private fun smoothPositionMs(
    realtimeMs: Long = SystemClock.elapsedRealtime(),
  ): Long {
    if (clockAnchorRealtimeMs == 0L || isPaused()) return clockAnchorPositionMs

    val elapsedMs = realtimeMs - clockAnchorRealtimeMs
    return clockAnchorPositionMs + (elapsedMs * playbackSpeed().coerceAtLeast(0.0)).toLong()
  }

  private fun anchorClock(
    positionMs: Long,
    realtimeMs: Long = SystemClock.elapsedRealtime(),
  ) {
    clockAnchorPositionMs = positionMs
    clockAnchorRealtimeMs = realtimeMs
  }

  private fun createParser(file: File): BaseDanmakuParser? =
    runCatching {
      val loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI)
      loader.load(FileInputStream(file))
      BiliDanmakuParser().apply {
        load(loader.dataSource)
      }
    }.onFailure { e ->
      Log.e(TAG, "Failed to create danmaku parser for ${file.absolutePath}", e)
    }.getOrNull()

  private fun createDanmakuContext(): DanmakuContext {
    val maxLines = hashMapOf(BaseDanmaku.TYPE_SCROLL_RL to 8)
    val preventOverlapping = hashMapOf(
      BaseDanmaku.TYPE_SCROLL_RL to true,
      BaseDanmaku.TYPE_FIX_TOP to true,
      BaseDanmaku.TYPE_FIX_BOTTOM to true,
    )

    return DanmakuContext.create()
      .setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3.0f)
      .setDuplicateMergingEnabled(false)
      .setScrollSpeedFactor(1.2f)
      .setScaleTextSize(1.0f)
      .setMaximumLines(maxLines)
      .preventOverlapping(preventOverlapping)
      .setDanmakuMargin(32)
  }

  private fun findMatchingDanmakuFile(
    originalUri: Uri?,
    playablePath: String?,
  ): File? {
    val videoFile = playablePath?.toLocalFile()
      ?: originalUri?.takeIf { it.scheme == "file" }?.path?.let(::File)
      ?: return null

    if (!videoFile.exists()) return null
    val parent = videoFile.parentFile ?: return null
    val expectedName = "${videoFile.nameWithoutExtension}.xml"
    val directMatch = File(parent, expectedName)
    if (directMatch.isFile) return directMatch

    return parent.listFiles()?.firstOrNull {
      it.isFile && it.name.equals(expectedName, ignoreCase = true)
    }
  }

  private fun String.toLocalFile(): File? {
    val path = when {
      startsWith("file://") -> Uri.parse(this).path
      startsWith("/") -> this
      else -> null
    } ?: return null

    return File(path)
  }

  private companion object {
    const val TAG = "DanmakuOverlay"
    const val SEEK_SYNC_THRESHOLD_MS = 3_000L
    const val CLOCK_CORRECTION_THRESHOLD_MS = 120L
    const val MAX_CLOCK_CORRECTION_STEP_MS = 80L
  }
}
