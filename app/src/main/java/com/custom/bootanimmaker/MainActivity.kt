package com.custom.bootanimmaker

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.DisplayMetrics
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private var picked: Uri? = null
    private var lastModule: File? = null

    private lateinit var pickedText: TextView
    private lateinit var fpsIn: EditText
    private lateinit var secIn: EditText
    private lateinit var divIn: EditText
    private lateinit var holdBox: CheckBox
    private lateinit var buildBtn: Button
    private lateinit var installBtn: Button
    private lateinit var logView: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            picked = uri
            pickedText.text = "Picked: " + (uri.lastPathSegment ?: uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }

        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
        }
        fun field(hint: String, v: String) = EditText(this).apply {
            this.hint = hint; setText(v); inputType = InputType.TYPE_CLASS_NUMBER
        }

        val (sw, sh) = screenSize()
        root.addView(TextView(this).apply {
            textSize = 20f
            text = "BootAnim Maker"
        })
        root.addView(TextView(this).apply {
            text = "Screen: ${sw}x${sh}\nTarget path: /${modulePath()}/bootanimation.zip"
        })
        root.addView(btn("Pick video / GIF") { picker.launch(arrayOf("video/*", "image/gif")) })
        pickedText = TextView(this).apply { text = "Nothing picked" }
        root.addView(pickedText)

        fpsIn = field("FPS (1-60)", "24")
        secIn = field("Max seconds (1-60)", "10")
        divIn = field("Downscale divisor (1 = full res, 2 = half...)", "1")
        root.addView(fpsIn); root.addView(secIn); root.addView(divIn)
        holdBox = CheckBox(this).apply {
            text = "Play once, then hold last frame until boot finishes"
            isChecked = true
        }
        root.addView(holdBox)

        buildBtn = btn("Build module zip") { build() }
        installBtn = btn("Install via root (Magisk/KSU/APatch)") { install() }
        installBtn.isEnabled = false
        root.addView(buildBtn)
        root.addView(installBtn)

        logView = TextView(this).apply { setTextIsSelectable(true) }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------- helpers ----------

    private fun log(msg: String) = runOnUiThread { logView.append(msg + "\n") }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(m)
        // boot animations are portrait on phones
        return minOf(m.widthPixels, m.heightPixels) to maxOf(m.widthPixels, m.heightPixels)
    }

    // product/ is checked by bootanimation before system/, so it wins either way
    private fun modulePath(): String = when {
        File("/product/media/bootanimation.zip").exists() -> "system/product/media"
        File("/system/product/media/bootanimation.zip").exists() -> "system/product/media"
        File("/system/media/bootanimation.zip").exists() -> "system/media"
        else -> "system/product/media"
    }

    private fun putStored(zos: ZipOutputStream, name: String, data: ByteArray) {
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.size = data.size.toLong()
        e.compressedSize = e.size
        val crc = CRC32(); crc.update(data); e.crc = crc.value
        zos.putNextEntry(e); zos.write(data); zos.closeEntry()
    }

    private fun crcOf(f: File): Long {
        val crc = CRC32()
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; crc.update(buf, 0, n) }
        }
        return crc.value
    }

    private fun drawFit(c: Canvas, src: Bitmap, w: Int, h: Int, p: Paint) {
        val s = minOf(w / src.width.toFloat(), h / src.height.toFloat())
        val dw = src.width * s
        val dh = src.height * s
        val l = (w - dw) / 2f
        val t = (h - dh) / 2f
        c.drawBitmap(src, null, RectF(l, t, l + dw, t + dh), p)
    }

    // ---------- build ----------

    private fun build() {
        val uri = picked
        if (uri == null) {
            Toast.makeText(this, "Pick a video or GIF first", Toast.LENGTH_SHORT).show(); return
        }
        val fps = fpsIn.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 24
        val maxSec = secIn.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 10
        val div = divIn.text.toString().toIntOrNull()?.coerceIn(1, 8) ?: 1
        val isGif = (contentResolver.getType(uri) ?: "") == "image/gif"
        val hold = holdBox.isChecked

        buildBtn.isEnabled = false
        installBtn.isEnabled = false
        logView.text = ""

        Thread {
            try {
                val (sw, sh) = screenSize()
                val w = (sw / div) and 1.inv()
                val h = (sh / div) and 1.inv()
                log("Frame size ${w}x${h} @ ${fps}fps, max ${maxSec}s, gif=$isGif")

                val inner = File(cacheDir, "bootanimation.zip")
                val frames = writeInner(uri, isGif, w, h, fps, maxSec, hold, inner)
                log("Frames: $frames, inner zip ${inner.length() / 1024 / 1024} MB (STORED)")

                val outDir = getExternalFilesDir(null) ?: cacheDir
                val module = File(outDir, "BootAnimModule.zip")
                val path = modulePath()
                val prop = """
                    id=custombootanim
                    name=Custom Boot Animation
                    version=v1.0
                    versionCode=1
                    author=BootAnimMaker
                    description=Custom boot animation made with BootAnim Maker
                """.trimIndent() + "\n"

                ZipOutputStream(BufferedOutputStream(FileOutputStream(module))).use { z ->
                    val pe = ZipEntry("module.prop")
                    z.putNextEntry(pe); z.write(prop.toByteArray()); z.closeEntry()

                    val e = ZipEntry("$path/bootanimation.zip")
                    e.method = ZipEntry.STORED
                    e.size = inner.length()
                    e.compressedSize = e.size
                    e.crc = crcOf(inner)
                    z.putNextEntry(e)
                    inner.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
                inner.delete()
                lastModule = module
                log("Module: ${module.absolutePath}")

                try {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "BootAnimModule_${System.currentTimeMillis()}.zip")
                        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    }
                    val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    if (u != null) {
                        contentResolver.openOutputStream(u)!!.use { o ->
                            module.inputStream().use { it.copyTo(o) }
                        }
                        log("Also copied to Downloads")
                    }
                } catch (e: Exception) {
                    log("Downloads copy failed: ${e.message}")
                }
                log("Done. Flash it in Magisk/KSU or hit Install.")
                runOnUiThread { installBtn.isEnabled = true }
            } catch (e: Throwable) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runOnUiThread { buildBtn.isEnabled = true }
            }
        }.start()
    }

    private fun writeInner(
        uri: Uri, isGif: Boolean, w: Int, h: Int, fps: Int, maxSec: Int, hold: Boolean, out: File
    ): Int {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val baos = ByteArrayOutputStream()
        var count = 0

        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            // desc.txt first: size fps, then parts (play once + hold last frame, or loop)
            val parts = if (hold) "p 1 0 part0\np 0 0 part1\n" else "p 0 0 part0\n"
            putStored(zos, "desc.txt", "$w $h $fps\n$parts".toByteArray())
            var lastPng: ByteArray? = null

            val emit = {
                baos.reset()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val b = baos.toByteArray()
                lastPng = b
                putStored(zos, "part0/%05d.png".format(count), b)
                count++
                if (count % 10 == 0) log("...$count frames")
            }

            if (isGif) {
                val bytes = contentResolver.openInputStream(uri)!!.use(InputStream::readBytes)
                val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("Could not decode GIF")
                val dur = movie.duration().let { if (it <= 0) 1000 else it }
                val totalMs = minOf(dur, maxSec * 1000)
                val n = maxOf(1, totalMs * fps / 1000)
                val s = minOf(w / movie.width().toFloat(), h / movie.height().toFloat())
                for (i in 0 until n) {
                    movie.setTime((i * 1000 / fps) % dur)
                    canvas.drawColor(Color.BLACK)
                    canvas.save()
                    canvas.translate((w - movie.width() * s) / 2f, (h - movie.height() * s) / 2f)
                    canvas.scale(s, s)
                    movie.draw(canvas, 0f, 0f)
                    canvas.restore()
                    emit()
                }
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(this, uri)
                    val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 1000L
                    val totalMs = minOf(durMs, maxSec * 1000L)
                    val n = maxOf(1L, totalMs * fps / 1000).toInt()
                    for (i in 0 until n) {
                        val t = i * 1_000_000L / fps
                        val f = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                        canvas.drawColor(Color.BLACK)
                        drawFit(canvas, f, w, h, paint)
                        f.recycle()
                        emit()
                    }
                } finally {
                    r.release()
                }
            }

            // hold part: last frame loops forever until boot finishes
            if (hold) {
                val lp = lastPng ?: throw IllegalStateException("No frames extracted")
                putStored(zos, "part1/00000.png", lp)
                putStored(zos, "part1/00001.png", lp)
            }
        }
        bmp.recycle()
        if (count == 0) throw IllegalStateException("No frames extracted")
        return count
    }

    // ---------- install ----------

    private fun install() {
        val m = lastModule ?: return
        installBtn.isEnabled = false
        log("Requesting root...")
        Thread {
            try {
                val p = m.absolutePath
                val cmd = "magisk --install-module '$p' || /data/adb/ksud module install '$p' || /data/adb/apd module install '$p'"
                val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                val outText = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                log(outText.trim())
                log(if (code == 0) "Installed. Reboot to apply." else "Install failed (exit $code). Flash the zip manually.")
            } catch (e: Exception) {
                log("Root install failed: ${e.message}")
            } finally {
                runOnUiThread { installBtn.isEnabled = true }
            }
        }.start()
    }
}
