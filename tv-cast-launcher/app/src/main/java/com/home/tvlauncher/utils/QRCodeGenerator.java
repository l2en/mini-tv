package com.home.tvlauncher.utils;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * QR 码生成器（基于 ZXing）
 */
public class QRCodeGenerator {

    private static final String TAG = "QRCodeGenerator";

    /**
     * 生成 QR 码 Bitmap
     * @param text 要编码的文本
     * @param size 输出图片尺寸（像素）
     * @return QR 码 Bitmap，失败返回 null
     */
    public static Bitmap generate(String text, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();

            Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints);

            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            int[] pixels = new int[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

            Log.d(TAG, "QR 码生成成功: " + text + " (" + width + "x" + height + ")");
            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "QR 码生成失败: " + e.getMessage(), e);
            return null;
        }
    }
}
