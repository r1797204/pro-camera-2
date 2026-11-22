package com.example.procam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private data class VideoCfg(val size: Size, val fps: Int) {
        val label: String
            get() = (if (size.height >= 2160) "4K" else "${size.height}p") + "·" + fps
    }

    // ---------------------------------------------------------------- views
    private lateinit var leftPane: FrameLayout
    private lateinit var previewBox: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var controls: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var shutterBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var lensBtn: Button
    private lateinit var isoBar: SeekBar
    private lateinit var shutBar: SeekBar
    private lateinit var focBar: SeekBar
    private lateinit var isoTv: TextView
    private lateinit var shutTv: TextView
    private lateinit var focTv: TextView
    private lateinit var aeBtn: Button
    private lateinit var afBtn: Button
    private lateinit var wbBtn: Button
    private lateinit var rawBtn: Button
    private lateinit var aspectBtn: Button
    private lateinit var cfgBtn: Button
    private lateinit var codecBtn: Button
    private lateinit var bitrateBtn: Button
    private lateinit var gridBtn: Button
    private lateinit var zebraBtn: Button
    private lateinit var peakBtn: Button
    private lateinit var histBtn: Button

    // -------------------------------------------------------------- threads
    private val ui = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null
    private var anaThread: HandlerThread? = null
    private var anaHandler: Handler? = null

    // --------------------------------------------------------------- camera
    private lateinit var cm: CameraManager
    private var cameraIds: List<String> = emptyList()
    private var camIndex = 0
    private var chars: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var curSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var sessionGen = 0
    private var opening = false
    private var permAsked = false

    private var sensorOrientation = 90
    private var isFront = false
    private var manualSensor = false
    private var rawSupported = false
    private var isoRange: Range<Int> = Range(100, 3200)
    private var expRange: Range<Long> = Range(100_000L, 100_000_000L)
    private var minFocus = 0f
    private var afModes: IntArray = IntArray(0)
    private var fpsRanges: Array<Range<Int>> = emptyArray()
    private var wbList: List<Pair<Int, String>> = emptyList()
    private var videoCfgs: List<VideoCfg> = emptyList()
    private var previewSize = Size(1920, 1080)

    // ------------------------------------------------------------- settings
    private var isVideo = false
    @Volatile private var recording = false
    private var autoExposure = true
    private var autoFocus = true
    private var iso = 400
    private var expNs = 16_666_667L
    private var focusD = 0f
    private var wbIdx = 0
    private var rawOn = false
    private var aspect169 = false
    private var videoIdx = 0
    private var hevc = true
    private val bitrates = intArrayOf(20, 50, 100, 150)
    private var bitrateIdx = 1

    @Volatile private var lastIso = 400
    @Volatile private var lastExp = 16_666_667L
    @Volatile private var lastFocus = 0f
    private var lastUi = 0L

    // ------------------------------------------------------------ recording
    private var recorder: MediaRecorder? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recUri: Uri? = null
    private var recStart = 0L

    // ----------------------------------------------------------------- RAW
    private val rawImages = HashMap<Long, Image>()
    private val rawResults = HashMap<Long, TotalCaptureResult>()

    // ------------------------------------------------------------- analysis
    private var anaBmp: Bitmap? = null
    private val anaW = 320

    // ======================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cm = getSystemService(CAMERA_SERVICE) as CameraManager
        listCameras()
        buildUi()
        if (cameraIds.isEmpty()) {
            Toast.makeText(this, "Камеры не найдены", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("cam").also { it.start() }
        bg = Handler(bgThread!!.looper)
        anaThread = HandlerThread("ana").also { it.start() }
        anaHandler = Handler(anaThread!!.looper)
        anaHandler?.post(anaRunnable)
        maybeOpen()
    }

    override fun onPause() {
        stopRecording(restart = false)
        anaHandler?.removeCallbacksAndMessages(null)
        closeCamera()
        bgThread?.quitSafely()
        anaThread?.quitSafely()
        try {
            bgThread?.join()
            anaThread?.join()
        } catch (e: InterruptedException) {
        }
        bgThread = null
        bg = null
        anaThread = null
        anaHandler = null
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast("Нужно разрешение на камеру")
        } else if (bgThread != null) {
            maybeOpen()
        }
    }

    // ============================================================== UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun style(b: Button, on: Boolean) {
        b.setBackgroundColor(if (on) Color.argb(210, 46, 125, 50) else Color.argb(110, 0, 0, 0))
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), 0, dp(10), 0)
        setBackgroundColor(Color.argb(110, 0, 0, 0))
        setOnClickListener { onClick() }
    }

    private fun label(text: String, widthDp: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 12f
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun sliderRow(name: String, onUser: (Int) -> Unit): Pair<SeekBar, TextView> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val value = label("", 84)
        val bar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) onUser(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        row.addView(label(name, 62))
        row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value)
        controls.addView(row)
        return Pair(bar, value)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }

        leftPane = FrameLayout(this)
        root.addView(leftPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        previewBox = FrameLayout(this)
        textureView = TextureView(this)
        overlay = OverlayView(this)
        previewBox.addView(textureView, FrameLayout.LayoutParams(-1, -1))
        previewBox.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        leftPane.addView(previewBox, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        infoText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        leftPane.addView(infoText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val isoRow = sliderRow("ISO") { p ->
            goManualExposure()
            iso = isoFromP(p)
            updateLabels(); updateRepeating()
        }
        isoBar = isoRow.first; isoTv = isoRow.second
        val shRow = sliderRow("Выдержка") { p ->
            goManualExposure()
            expNs = expFromP(p)
            updateLabels(); updateRepeating()
        }
        shutBar = shRow.first; shutTv = shRow.second
        val foRow = sliderRow("Фокус") { p ->
            autoFocus = false
            focusD = p / 1000f * minFocus
            refreshUi(); updateLabels(); updateRepeating()
        }
        focBar = foRow.first; focTv = foRow.second

        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(b: Button) {
            strip.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) })
        }
        aeBtn = button("AE") {
            if (autoExposure) goManualExposure() else autoExposure = true
            refreshUi(); updateRepeating()
        }
        afBtn = button("AF") {
            autoFocus = !autoFocus
            if (!autoFocus) { focusD = lastFocus.coerceIn(0f, minFocus) }
            refreshUi(); updateRepeating()
        }
        wbBtn = button("WB") {
            if (wbList.isNotEmpty()) wbIdx = (wbIdx + 1) % wbList.size
            refreshUi(); updateRepeating()
        }
        rawBtn = button("RAW") {
            if (!rawSupported) toast("Эта камера не отдаёт RAW сторонним приложениям")
            else { rawOn = !rawOn; refreshUi(); startPreviewSession() }
        }
        aspectBtn = button("4:3") { aspect169 = !aspect169; refreshUi(); startPreviewSession() }
        cfgBtn = button("1080p·30") {
            if (!recording && videoCfgs.isNotEmpty()) {
                videoIdx = (videoIdx + 1) % videoCfgs.size
                refreshUi(); startPreviewSession()
            }
        }
        codecBtn = button("HEVC") { if (!recording) { hevc = !hevc; refreshUi() } }
        bitrateBtn = button("50 Мбит") { if (!recording) { bitrateIdx = (bitrateIdx + 1) % bitrates.size; refreshUi() } }
        gridBtn = button("Сетка") { overlay.grid = !overlay.grid; refreshUi(); overlay.invalidate() }
        zebraBtn = button("Зебра") { overlay.zebra = !overlay.zebra; refreshUi() }
        peakBtn = button("Пикинг") { overlay.peaking = !overlay.peaking; refreshUi() }
        histBtn = button("Гист.") { overlay.histogram = !overlay.histogram; refreshUi(); overlay.invalidate() }
        for (b in listOf(aeBtn, afBtn, wbBtn, rawBtn, aspectBtn, cfgBtn, codecBtn, bitrateBtn, gridBtn, zebraBtn, peakBtn, histBtn)) add(b)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
        controls.addView(scroll)
        leftPane.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        leftPane.addOnLayoutChangeListener { _, l, t, r, b, oL, oT, oR, oB ->
            if (r - l != oR - oL || b - t != oB - oT) layoutPreview()
        }

        // правая панель
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        root.addView(right, LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.MATCH_PARENT))

        modeBtn = button("ФОТО") { setMode(!isVideo) }
        shutterBtn = Button(this).apply { setOnClickListener { onShutter() } }
        lensBtn = button("Тыл") { switchLens() }
        val tuneBtn = button("Настройки") {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        right.addView(modeBtn, LinearLayout.LayoutParams(-1, dp(38)))
        right.addView(shutterBtn, LinearLayout.LayoutParams(dp(78), dp(78)).apply { topMargin = dp(14); bottomMargin = dp(14) })
        right.addView(lensBtn, LinearLayout.LayoutParams(-1, dp(38)))
        right.addView(tuneBtn, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(8) })

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { maybeOpen() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        setContentView(root)
        refreshUi()
    }

    private fun refreshUi() {
        modeBtn.text = if (isVideo) "ВИДЕО" else "ФОТО"
        lensBtn.text = lensLabel()
        aeBtn.text = if (autoExposure) "AE авто" else "AE ручн."
        style(aeBtn, autoExposure)
        afBtn.text = if (autoFocus) "AF авто" else "AF ручн."
        style(afBtn, autoFocus)
        wbBtn.text = wbList.getOrNull(wbIdx)?.second ?: "WB"
        rawBtn.text = "RAW"
        style(rawBtn, rawOn)
        rawBtn.alpha = if (rawSupported) 1f else 0.4f
        aspectBtn.text = if (aspect169) "16:9" else "4:3"
        cfgBtn.text = videoCfgs.getOrNull(videoIdx)?.label ?: "-"
        codecBtn.text = if (hevc) "HEVC" else "H.264"
        bitrateBtn.text = "${bitrates[bitrateIdx]} Мбит"
        style(gridBtn, overlay.grid)
        style(zebraBtn, overlay.zebra)
        style(peakBtn, overlay.peaking)
        style(histBtn, overlay.histogram)

        val photoOnly = if (isVideo) View.GONE else View.VISIBLE
        val videoOnly = if (isVideo) View.VISIBLE else View.GONE
        rawBtn.visibility = photoOnly
        aspectBtn.visibility = photoOnly
        cfgBtn.visibility = videoOnly
        codecBtn.visibility = videoOnly
        bitrateBtn.visibility = videoOnly

        isoBar.isEnabled = manualSensor
        shutBar.isEnabled = manualSensor
        focBar.isEnabled = minFocus > 0f
        aeBtn.alpha = if (manualSensor) 1f else 0.4f
        afBtn.alpha = if (minFocus > 0f) 1f else 0.4f

        updateShutter()
        updateLabels()
    }

    private fun updateShutter() {
        val d = GradientDrawable()
        if (isVideo) {
            d.setColor(Color.rgb(220, 30, 30))
            if (recording) {
                d.shape = GradientDrawable.RECTANGLE
                d.cornerRadius = dp(10).toFloat()
            } else {
                d.shape = GradientDrawable.OVAL
            }
        } else {
            d.shape = GradientDrawable.OVAL
            d.setColor(Color.WHITE)
        }
        d.setStroke(dp(4), Color.argb(170, 255, 255, 255))
        shutterBtn.background = d
    }

    private fun updateLabels() {
        val i = if (autoExposure) lastIso else iso
        val e = if (autoExposure) lastExp else expNs
        isoTv.text = i.toString()
        shutTv.text = fmtShutter(e)
        focTv.text = fmtFocus(if (autoFocus) lastFocus else focusD)
    }

    private fun refreshLive() {
        val sb = StringBuilder()
        if (recording) {
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            sb.append(String.format(Locale.US, "● %02d:%02d   ", s / 60, s % 60))
        }
        sb.append("ISO ").append(lastIso).append("   ").append(fmtShutter(lastExp))
        sb.append("   AF ").append(fmtFocus(lastFocus))
        infoText.text = sb.toString()
        if (autoExposure) {
            isoBar.progress = isoToP(lastIso)
            shutBar.progress = expToP(lastExp)
        }
        if (autoFocus && minFocus > 0f) focBar.progress = (lastFocus / minFocus * 1000f).roundToInt().coerceIn(0, 1000)
        updateLabels()
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ======================================================= formatting

    private fun fmtShutter(ns: Long): String {
        if (ns <= 0) return "-"
        return if (ns >= 400_000_000L) String.format(Locale.US, "%.1fs", ns / 1e9)
        else "1/" + (1e9 / ns).roundToInt()
    }

    private fun fmtFocus(d: Float): String =
        if (d < 0.05f) "∞" else String.format(Locale.US, "%.2fм", 1f / d)

    private fun logMap(p: Int, lo: Double, hi: Double): Double =
        if (hi <= lo) lo else lo * (hi / lo).pow(p / 1000.0)

    private fun logInv(v: Double, lo: Double, hi: Double): Int =
        if (hi <= lo || v <= lo) 0 else (1000.0 * ln(v / lo) / ln(hi / lo)).roundToInt().coerceIn(0, 1000)

    private fun isoFromP(p: Int): Int =
        logMap(p, isoRange.lower.toDouble(), isoRange.upper.toDouble()).roundToInt().coerceIn(isoRange.lower, isoRange.upper)

    private fun isoToP(v: Int): Int = logInv(v.toDouble(), isoRange.lower.toDouble(), isoRange.upper.toDouble())

    private fun expHi(): Double = min(expRange.upper, 1_000_000_000L).toDouble()

    private fun expFromP(p: Int): Long =
        logMap(p, expRange.lower.toDouble(), expHi()).toLong().coerceIn(expRange.lower, expRange.upper)

    private fun expToP(v: Long): Int = logInv(v.toDouble(), expRange.lower.toDouble(), expHi())

    private fun goManualExposure() {
        if (autoExposure) {
            autoExposure = false
            iso = lastIso.coerceIn(isoRange.lower, isoRange.upper)
            expNs = lastExp.coerceIn(expRange.lower, expRange.upper)
            refreshUi()
        }
    }

    // ======================================================== cameras

    private fun listCameras() {
        try {
            val ids = cm.cameraIdList.filter { id ->
                val c = cm.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
            }
            cameraIds = ids.sortedBy { id ->
                if (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraMetadata.LENS_FACING_BACK) 0 else 1
            }
        } catch (e: Exception) {
            cameraIds = emptyList()
        }
    }

    private fun lensLabel(): String {
        if (cameraIds.isEmpty()) return "-"
        val c = try { cm.getCameraCharacteristics(cameraIds[camIndex]) } catch (e: Exception) { return "-" }
        val front = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val eq = if (f != null && sz != null)
            (f * 43.27 / hypot(sz.width.toDouble(), sz.height.toDouble())).roundToInt() else null
        return (if (front) "Фронт" else "Тыл") + (if (eq != null) " ${eq}мм" else "") + " #" + cameraIds[camIndex]
    }

    private fun configureCamera(c: CameraCharacteristics) {
        chars = c
        sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        isFront = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        rawSupported = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
                (map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true)
        if (!rawSupported) rawOn = false

        isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
        expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 100_000_000L)
        minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray<Range<Int>>()
        iso = iso.coerceIn(isoRange.lower, isoRange.upper)
        expNs = expNs.coerceIn(expRange.lower, expRange.upper)
        focusD = focusD.coerceIn(0f, minFocus)

        val allWb = listOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO to "WB авто",
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "WB 3000K",
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "WB 4200K",
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "WB 5500K",
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "WB 6500K",
            CameraMetadata.CONTROL_AWB_MODE_SHADE to "WB 7500K"
        )
        val avail = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
        wbList = allWb.filter { avail.contains(it.first) }
        if (wbList.isEmpty()) wbList = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO to "WB авто")
        wbIdx = 0

        // доступные режимы видео
        val list = ArrayList<VideoCfg>()
        val sizes: Array<Size> = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray<Size>()
        for ((w, h) in listOf(3840 to 2160, 1920 to 1080, 1280 to 720)) {
            val sz = sizes.firstOrNull { it.width == w && it.height == h } ?: continue
            val minDur = map?.getOutputMinFrameDuration(MediaRecorder::class.java, sz) ?: 0L
            val maxFps = if (minDur > 0) (1e9 / minDur).toInt() + 1 else 30
            for (fps in intArrayOf(24, 30, 60)) if (fps <= maxFps) list.add(VideoCfg(sz, fps))
        }
        if (list.isEmpty()) list.add(VideoCfg(Size(1920, 1080), 30))
        videoCfgs = list
        videoIdx = list.indexOfFirst { it.size.height == 1080 && it.fps == 30 }.let { if (it >= 0) it else 0 }
    }

    private fun maybeOpen() {
        if (bg == null || !textureView.isAvailable || device != null || opening || cameraIds.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permAsked) {
                permAsked = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 100)
            }
            return
        }
        openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            if (camIndex >= cameraIds.size) camIndex = 0
            val id = cameraIds[camIndex]
            configureCamera(cm.getCameraCharacteristics(id))
            refreshUi()
            opening = true
            cm.openCamera(id, stateCb, bg)
        } catch (e: Exception) {
            opening = false
            toast("Не удалось открыть камеру: ${e.message}")
        }
    }

    private val stateCb = object : CameraDevice.StateCallback() {
        override fun onOpened(c: CameraDevice) {
            opening = false
            device = c
            runOnUiThread { startPreviewSession() }
        }

        override fun onDisconnected(c: CameraDevice) {
            opening = false
            c.close()
            if (device === c) device = null
        }

        override fun onError(c: CameraDevice, error: Int) {
            opening = false
            c.close()
            if (device === c) device = null
            toast("Ошибка камеры ($error)")
        }
    }

    private fun closeSession() {
        sessionGen++
        try { session?.close() } catch (e: Exception) { }
        session = null
        previewBuilder = null
        try { curSurface?.release() } catch (e: Exception) { }
        curSurface = null
    }

    private fun closeReaders() {
        synchronized(this) {
            for (i in rawImages.values) try { i.close() } catch (e: Exception) { }
            rawImages.clear()
            rawResults.clear()
        }
        try { jpegReader?.close() } catch (e: Exception) { }
        try { rawReader?.close() } catch (e: Exception) { }
        jpegReader = null
        rawReader = null
    }

    private fun closeCamera() {
        closeSession()
        closeReaders()
        try { device?.close() } catch (e: Exception) { }
        device = null
        opening = false
    }

    private fun switchLens() {
        if (recording || cameraIds.size < 2) return
        camIndex = (camIndex + 1) % cameraIds.size
        closeCamera()
        ui.postDelayed({ maybeOpen() }, 300)
    }

    private fun setMode(video: Boolean) {
        if (recording || isVideo == video) return
        isVideo = video
        refreshUi()
        startPreviewSession()
    }

    // ================================================== preview session

    private fun chooseSize(sizes: Array<Size>?, aspect: Float, maxW: Int): Size {
        val all = sizes?.toList().orEmpty()
        val ok = all.filter { it.width <= maxW && abs(it.width.toFloat() / it.height - aspect) < 0.02f }
        return ok.maxByOrNull { it.width.toLong() * it.height }
            ?: all.filter { it.width <= maxW }.minByOrNull { abs(it.width.toFloat() / it.height - aspect) }
            ?: Size(1280, 720)
    }

    private fun largestWithAspect(sizes: Array<Size>?, aspect: Float): Size {
        val all = sizes?.toList().orEmpty()
        val ok = all.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f }
        return ok.maxByOrNull { it.width.toLong() * it.height }
            ?: all.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(4000, 3000)
    }

    @Suppress("DEPRECATION")
    private fun startPreviewSession() {
        val dev = device ?: return
        val c = chars ?: return
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val tex = textureView.surfaceTexture ?: return

        closeSession()
        closeReaders()

        val extra = ArrayList<Surface>()
        if (isVideo) {
            val cfg = videoCfgs.getOrNull(videoIdx) ?: return
            previewSize = chooseSize(map.getOutputSizes(SurfaceTexture::class.java),
                cfg.size.width.toFloat() / cfg.size.height, 1920)
        } else {
            val target = if (aspect169) 16f / 9f else 4f / 3f
            val js = largestWithAspect(map.getOutputSizes(ImageFormat.JPEG), target)
            previewSize = chooseSize(map.getOutputSizes(SurfaceTexture::class.java),
                js.width.toFloat() / js.height, 1920)
            val jr = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 3)
            jr.setOnImageAvailableListener({ r -> onJpeg(r) }, bg)
            jpegReader = jr
            extra.add(jr.surface)
            if (rawOn && rawSupported) {
                val rs = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width.toLong() * it.height }
                if (rs != null) {
                    val rr = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 3)
                    rr.setOnImageAvailableListener({ r -> onRaw(r) }, bg)
                    rawReader = rr
                    extra.add(rr.surface)
                }
            }
        }
        layoutPreview()
        tex.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surf = Surface(tex)
        curSurface = surf
        val gen = ++sessionGen
        val outs = ArrayList<Surface>()
        outs.add(surf)
        outs.addAll(extra)

        try {
            dev.createCaptureSession(outs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != sessionGen || device == null) { s.close(); return }
                    session = s
                    try {
                        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        b.addTarget(surf)
                        previewBuilder = b
                        runOnUiThread { updateRepeating() }
                    } catch (e: Exception) {
                        toast("Ошибка превью: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen == sessionGen) toast("Камера не поддерживает эту комбинацию. Попробуйте выключить RAW или сменить режим")
                }
            }, bg)
        } catch (e: Exception) {
            toast("Ошибка сессии: ${e.message}")
        }
    }

    /** Подгоняем размер окна превью под пропорции кадра и поворачиваем картинку для альбомной ориентации. */
    @Suppress("DEPRECATION")
    private fun layoutPreview() {
        val cw = leftPane.width
        val ch = leftPane.height
        if (cw == 0 || ch == 0) return
        val pw = previewSize.width.toFloat()
        val ph = previewSize.height.toFloat()
        val scale = min(cw / pw, ch / ph)
        val bw = (pw * scale).toInt()
        val bh = (ph * scale).toInt()
        val lp = FrameLayout.LayoutParams(bw, bh, Gravity.CENTER)
        previewBox.layoutParams = lp

        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, bw.toFloat(), bh.toFloat())
        val bufferRect = RectF(0f, 0f, ph, pw)
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val s = max(bh / ph, bw / pw)
            matrix.postScale(s, s, cx, cy)
            matrix.postRotate(90f * (rotation - 2), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        textureView.setTransform(matrix)
    }

    // ================================================ capture settings

    private fun curFps(): Int = if (isVideo) (videoCfgs.getOrNull(videoIdx)?.fps ?: 30) else 30

    private fun fpsRangeFor(fps: Int): Range<Int>? =
        fpsRanges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }

    private fun applySettings(b: CaptureRequest.Builder, still: Boolean = false) {
        val fps = curFps()
        val frameNs = (1e9 / fps).toLong()
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        if (autoExposure || !manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            if (!still) fpsRangeFor(fps)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        } else {
            val e = if (isVideo && !still) min(expNs, frameNs) else expNs
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(e, frameNs))
        }

        if (autoFocus || minFocus <= 0f) {
            val wanted = if (isVideo) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            if (afModes.contains(wanted)) b.set(CaptureRequest.CONTROL_AF_MODE, wanted)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusD)
        }

        val wb = wbList.getOrNull(wbIdx)?.first ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
        b.set(CaptureRequest.CONTROL_AWB_MODE, wb)
    }

    private fun updateRepeating() {
        val b = previewBuilder ?: return
        val s = session ?: return
        try {
            applySettings(b)
            s.setRepeatingRequest(b.build(), previewCb, bg)
        } catch (e: Exception) {
        }
    }

    private val previewCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocus = it }
            val now = SystemClock.uptimeMillis()
            if (now - lastUi > 250) {
                lastUi = now
                runOnUiThread { refreshLive() }
            }
        }
    }

    // ================================================== orientation

    @Suppress("DEPRECATION")
    private fun deviceRotationDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun jpegOrientation(): Int {
        val d = deviceRotationDegrees()
        return if (isFront) (sensorOrientation + d) % 360 else (sensorOrientation - d + 360) % 360
    }

    private fun exifOrientation(): Int = when (jpegOrientation()) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    // ===================================================== shutter

    private fun onShutter() {
        if (!isVideo) takePhoto()
        else if (recording) stopRecording() else startRecording()
    }

    private fun takePhoto() {
        val dev = device ?: return
        val s = session ?: return
        val jr = jpegReader ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jr.surface)
            val rr = rawReader
            if (rr != null) {
                b.addTarget(rr.surface)
                b.setTag("raw")
            }
            applySettings(b, still = true)
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            b.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
            s.capture(b.build(), stillCb, bg)
        } catch (e: Exception) {
            toast("Не удалось сделать снимок: ${e.message}")
        }
    }

    private val stillCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            if (request.tag == "raw") {
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                if (ts != null) offerRaw(ts, null, result)
            }
        }
    }

    // ================================================== saving media

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun insertMedia(collection: Uri, name: String, mime: String): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ProCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try { contentResolver.insert(collection, v) } catch (e: Exception) { null }
    }

    private fun publish(uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        try { contentResolver.update(uri, v, null, null) } catch (e: Exception) { }
    }

    private fun onJpeg(r: ImageReader) {
        val img = (try { r.acquireNextImage() } catch (e: Exception) { null }) ?: return
        try {
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val uri = insertMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "PROCAM_${stamp()}.jpg", "image/jpeg")
            if (uri == null) {
                toast("Не удалось сохранить фото")
            } else {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                publish(uri)
                toast("Фото сохранено (DCIM/ProCam)")
            }
        } catch (e: Exception) {
            toast("Ошибка сохранения: ${e.message}")
        } finally {
            img.close()
        }
    }

    private fun onRaw(r: ImageReader) {
        val img = (try { r.acquireNextImage() } catch (e: Exception) { null }) ?: return
        offerRaw(img.timestamp, img, null)
    }

    @Synchronized
    private fun offerRaw(ts: Long, img: Image?, res: TotalCaptureResult?) {
        if (img != null) rawImages[ts] = img
        if (res != null) rawResults[ts] = res
        val i = rawImages[ts]
        val rs = rawResults[ts]
        if (i != null && rs != null) {
            rawImages.remove(ts)
            rawResults.remove(ts)
            writeDng(i, rs)
        }
    }

    private fun writeDng(img: Image, res: TotalCaptureResult) {
        try {
            val c = chars ?: return
            val uri = insertMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "PROCAM_${stamp()}.dng", "image/x-adobe-dng")
            if (uri == null) { toast("Не удалось сохранить RAW"); return }
            val dc = DngCreator(c, res)
            dc.setOrientation(exifOrientation())
            contentResolver.openOutputStream(uri)?.use { dc.writeImage(it, img) }
            dc.close()
            publish(uri)
            toast("RAW (DNG) сохранён")
        } catch (e: Exception) {
            toast("Ошибка RAW: ${e.message}")
        } finally {
            img.close()
        }
    }

    // ===================================================== video

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    @Suppress("DEPRECATION")
    private fun startRecording() {
        val dev = device ?: return
        val cfg = videoCfgs.getOrNull(videoIdx) ?: return
        val tex = textureView.surfaceTexture ?: return
        val uri = insertMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "PROCAM_${stamp()}.mp4", "video/mp4")
        if (uri == null) { toast("Не удалось создать файл"); return }
        val pfd = try { contentResolver.openFileDescriptor(uri, "w") } catch (e: Exception) { null }
        if (pfd == null) {
            try { contentResolver.delete(uri, null, null) } catch (e: Exception) { }
            toast("Не удалось открыть файл"); return
        }
        val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rec = newRecorder()
        try {
            if (audio) rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(pfd.fileDescriptor)
            rec.setVideoEncodingBitRate(bitrates[bitrateIdx] * 1_000_000)
            rec.setVideoFrameRate(cfg.fps)
            rec.setVideoSize(cfg.size.width, cfg.size.height)
            rec.setVideoEncoder(if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            if (audio) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(192_000)
                rec.setAudioSamplingRate(48_000)
            }
            rec.setOrientationHint(jpegOrientation())
            rec.prepare()
        } catch (e: Exception) {
            try { rec.release() } catch (e2: Exception) { }
            try { pfd.close() } catch (e2: Exception) { }
            try { contentResolver.delete(uri, null, null) } catch (e2: Exception) { }
            toast("Ошибка рекордера: ${e.message}")
            return
        }
        recorder = rec
        recPfd = pfd
        recUri = uri

        closeSession()
        tex.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surf = Surface(tex)
        curSurface = surf
        val gen = ++sessionGen
        try {
            dev.createCaptureSession(listOf(surf, rec.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != sessionGen || device == null) { s.close(); return }
                    session = s
                    try {
                        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        b.addTarget(surf)
                        b.addTarget(rec.surface)
                        applySettings(b)
                        previewBuilder = b
                        s.setRepeatingRequest(b.build(), previewCb, bg)
                        rec.start()
                        recStart = SystemClock.elapsedRealtime()
                        recording = true
                        runOnUiThread { refreshUi() }
                    } catch (e: Exception) {
                        toast("Не удалось начать запись: ${e.message}")
                        runOnUiThread { stopRecording() }
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    toast("Эта комбинация разрешения и fps не поддерживается")
                    runOnUiThread { stopRecording() }
                }
            }, bg)
        } catch (e: Exception) {
            toast("Ошибка сессии: ${e.message}")
            stopRecording()
        }
    }

    private fun stopRecording(restart: Boolean = true) {
        if (!recording && recorder == null) return
        val wasRecording = recording
        recording = false
        try { session?.stopRepeating() } catch (e: Exception) { }
        closeSession()
        val rec = recorder
        val uri = recUri
        val pfd = recPfd
        recorder = null
        recPfd = null
        recUri = null
        var ok = wasRecording
        try { rec?.stop() } catch (e: RuntimeException) { ok = false }
        try { rec?.release() } catch (e: Exception) { }
        try { pfd?.close() } catch (e: Exception) { }
        if (uri != null) {
            if (ok) {
                publish(uri)
                toast("Видео сохранено (DCIM/ProCam)")
            } else {
                try { contentResolver.delete(uri, null, null) } catch (e: Exception) { }
            }
        }
        if (restart) {
            refreshUi()
            startPreviewSession()
        }
    }

    // ================================================== analysis

    private val anaRunnable = object : Runnable {
        override fun run() {
            try { analyze() } catch (e: Exception) { }
            anaHandler?.postDelayed(this, 100)
        }
    }

    private fun analyze() {
        val zebra = overlay.zebra
        val peak = overlay.peaking
        val hist = overlay.histogram
        if (!zebra && !peak && !hist) return
        if (device == null || !textureView.isAvailable) return

        val aw = anaW
        val ah = max(2, aw * previewSize.height / previewSize.width)
        var b = anaBmp
        if (b == null || b.width != aw || b.height != ah) {
            b = Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888)
            anaBmp = b
        }
        val bmp = b ?: return
        textureView.getBitmap(bmp)

        val px = IntArray(aw * ah)
        bmp.getPixels(px, 0, aw, 0, 0, aw, ah)
        val luma = IntArray(aw * ah)
        val h64 = IntArray(64)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val bl = c and 255
            val l = (r * 77 + g * 150 + bl * 29) shr 8
            luma[i] = l
            h64[l shr 2]++
        }

        val out = IntArray(aw * ah)
        if (zebra) {
            val zc = Color.argb(200, 255, 40, 40)
            for (y in 0 until ah) for (x in 0 until aw) {
                if (luma[y * aw + x] >= 235 && (((x + y) shr 2) and 1) == 0) out[y * aw + x] = zc
            }
        }
        if (peak) {
            val pc = Color.argb(255, 0, 255, 110)
            for (y in 0 until ah - 1) for (x in 0 until aw - 1) {
                val i = y * aw + x
                val g = abs(luma[i] - luma[i + 1]) + abs(luma[i] - luma[i + aw])
                if (g > 40) out[i] = pc
            }
        }
        overlay.post { overlay.setAnalysis(out, aw, ah, h64) }
    }
}
