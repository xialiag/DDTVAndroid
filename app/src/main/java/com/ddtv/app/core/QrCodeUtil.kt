package com.ddtv.app.core

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.ByteArrayOutputStream

/**
 * 本地二维码生成（ZXing），B站粉配色
 */
object QrCodeUtil {

    private const val FG = 0xFFFB7299.toInt()   // B站粉
    private const val BG = 0xFFFFFFFF.toInt()

    /** 生成 QR 码的 base64 data URL，可直接用于 WebView <img> 标签 */
    fun generateBase64Png(text: String, size: Int = 512): String {
        return try {
            val bitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val w = bitMatrix.width
            val h = bitMatrix.height
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            for (x in 0 until w) {
                for (y in 0 until h) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) FG else BG)
                }
            }
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            bitmap.recycle()
            "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.e("QrCode", "QR码生成失败: ${e.message}")
            ""
        }
    }
}
