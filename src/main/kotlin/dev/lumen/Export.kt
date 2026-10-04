package dev.lumen

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.Size

/** Decoded in sRGB (P3 photos convert once, up front), scaled by `scale(full size)` if below 1. Returns the full size too. */
fun Context.decode(uri: Uri, scale: (Size) -> Float): Pair<Bitmap, Size> {
    var size = Size(1, 1)
    val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
        size = info.size
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        val k = scale(info.size)
        if (k < 1) d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
    }
    return b to size
}

class ExportOptions(val format: String, val long: Int, val quality: Int, val meta: Boolean, val location: Boolean, val watermark: String) {
    companion object {
        fun saved(ctx: Context) = ctx.getSharedPreferences("export", Context.MODE_PRIVATE).run {
            ExportOptions(getString("format", "JPEG")!!, getInt("long", 0), getInt("quality", 92), getBoolean("meta", true),
                false, getString("watermark", "")!!)
        }
    }
    fun save(ctx: Context) = ctx.getSharedPreferences("export", Context.MODE_PRIVATE).edit().putString("format", format).putInt("long", long)
        .putInt("quality", quality).putBoolean("meta", meta).putString("watermark", watermark).apply()
    // ponytail: "Keep location" is deliberately not remembered: GPS goes out only when asked for this export
}

private val EXIF_TAGS = listOf(
    ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, "LensMake", "LensModel", // framework ExifInterface has no constants for these
    ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
    ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
)
private val GPS_TAGS = listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF)

/** Watermark text, bottom right, sized to the photo, white with a soft shadow. */
private fun watermark(b: Bitmap, text: String): Bitmap {
    if (text.isBlank()) return b
    val out = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)
    val size = maxOf(out.width, out.height) * 0.025f
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt(); textSize = size; textAlign = Paint.Align.RIGHT; setShadowLayer(size * 0.15f, 0f, 0f, 0x99000000.toInt())
    }
    Canvas(out).drawText(text, out.width - size, out.height - size, p)
    return out
}

/**
 * Renders `e` on `source` at the requested size and writes it to Pictures/Lumen. Returns the new uri and its size, or null.
 * Tries the requested size first and 4096 px if memory runs out (8 bytes/px: decode + output).
 */
fun Context.exportPhoto(source: Uri, e: Edit, o: ExportOptions): Pair<Uri, Size>? {
    val out = listOf(o.long.takeIf { it > 0 } ?: Int.MAX_VALUE, 4096).distinct().firstNotNullOfOrNull { target ->
        runCatching {
            watermark(Look(decode(source) { s -> e.outSize(s.width, s.height).let { (w, h) -> target.toFloat() / maxOf(w, h) } }.first).render(e), o.watermark)
        }.onFailure { Log.w("Lumen", "export at $target", it) }.getOrNull()
    } ?: return null
    val (mime, ext, fmt) = when (o.format) {
        "PNG" -> Triple("image/png", "png", Bitmap.CompressFormat.PNG)
        "WebP" -> Triple("image/webp", "webp", Bitmap.CompressFormat.WEBP_LOSSY)
        else -> Triple("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG)
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Lumen_${System.currentTimeMillis()}.$ext")
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lumen")
        put(MediaStore.Images.Media.IS_PENDING, 1) // hidden from other apps until EXIF is written
    }
    val dest = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    val ok = contentResolver.openOutputStream(dest)?.use { out.compress(fmt, o.quality, it) } == true
    if (ok && o.meta) runCatching { copyExif(source, dest, o.location) }.onFailure { Log.w("Lumen", "exif", it) } // metadata is best effort
    if (!ok) { contentResolver.delete(dest, null, null); return null } // no half-written file in Pictures/Lumen
    contentResolver.update(dest, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    return if (ok) dest to Size(out.width, out.height) else null
}

/** Camera and date metadata survive export; location only when asked (and only if ACCESS_MEDIA_LOCATION is granted). */
private fun Context.copyExif(from: Uri, to: Uri, location: Boolean) {
    // MediaStore strips location from what it hands out unless the app asks for the original with the permission.
    val read = if (location && from.authority == MediaStore.AUTHORITY) runCatching { MediaStore.setRequireOriginal(from) }.getOrDefault(from) else from
    val src = contentResolver.openInputStream(read)?.use { ExifInterface(it) }
    contentResolver.openFileDescriptor(to, "rw")!!.use { fd ->
        ExifInterface(fd.fileDescriptor).apply {
            (EXIF_TAGS + if (location) GPS_TAGS else emptyList()).forEach { t -> src?.getAttribute(t)?.let { setAttribute(t, it) } }
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            setAttribute(ExifInterface.TAG_SOFTWARE, "Lumen")
            saveAttributes()
        }
    }
}
