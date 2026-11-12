package com.ostrava.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max

/** Photo loading and image export for the share screen. */
object ShareOutput {

    private const val MAX_PHOTO_DIMENSION = 2160

    val canSaveToGallery: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Decodes a picked/captured photo, downscaled and upright, as a software bitmap. */
    fun loadPhoto(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Hardware bitmaps can't be drawn onto the software canvas the card uses.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val largest = max(info.size.width, info.size.height)
                if (largest > MAX_PHOTO_DIMENSION) {
                    val scale = MAX_PHOTO_DIMENSION.toFloat() / largest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            loadPhotoLegacy(context, uri)
        }
    } catch (_: Exception) {
        null
    }

    private fun loadPhotoLegacy(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_PHOTO_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** A content URI the camera app can write a new photo into. */
    fun newCameraUri(context: Context): Uri {
        val file = File(exportsDir(context), "camera_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Writes the card to the cache and returns a share intent for it. */
    fun shareIntent(context: Context, bitmap: Bitmap, baseName: String, photographic: Boolean, subject: String): Intent {
        val file = File(exportsDir(context), "$baseName.${extension(photographic)}")
        file.outputStream().use { compress(bitmap, photographic, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(photographic)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share activity")
    }

    /** Saves the card into Pictures/Ostrava. Requires Android 10+ (see [canSaveToGallery]). */
    fun saveToGallery(context: Context, bitmap: Bitmap, baseName: String, photographic: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName.${extension(photographic)}")
            put(MediaStore.Images.Media.MIME_TYPE, mimeType(photographic))
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Ostrava")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { compress(bitmap, photographic, it) } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun exportsDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    // Photos compress far better as JPEG; flat gradients stay crisp as PNG.
    private fun extension(photographic: Boolean) = if (photographic) "jpg" else "png"

    private fun mimeType(photographic: Boolean) = if (photographic) "image/jpeg" else "image/png"

    private fun compress(bitmap: Bitmap, photographic: Boolean, out: java.io.OutputStream) {
        if (photographic) bitmap.compress(Bitmap.CompressFormat.JPEG, 93, out)
        else bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
}
