package io.relay.app.client

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File

class PairingPhotoProvider : FileProvider()

internal fun pairingPhoto(context: Context): File =
    File(File(context.cacheDir, "pairing-photo").apply { mkdirs() }, "pairing.jpg")

internal fun readInvitationImage(context: Context, uri: Uri): ClientTarget? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    require(longest in 1..32_768)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = Integer.highestOneBit(maxOf(1, longest / 1600))
    }
    val image = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return null
    try {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))
        ClientTarget.fromInvitation(result.text)
    } finally {
        image.recycle()
    }
}.getOrNull()
