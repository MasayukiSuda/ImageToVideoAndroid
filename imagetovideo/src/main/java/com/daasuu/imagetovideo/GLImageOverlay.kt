package com.daasuu.imagetovideo

import android.graphics.*
import android.media.ExifInterface
import android.util.Size
import java.io.File
import java.io.IOException

internal class GLImageOverlay(
  private val path: String,
  size: Size,
  bitmapCreateComplete: () -> Unit
) : GLOverlayDraw() {

  private val width: Int
  private val height: Int
  private var drawBitmap: Bitmap? = null
  private var originalBitmap: Bitmap? = null

  private val matrix: Matrix
  private val paintFlagsDrawFilter: PaintFlagsDrawFilter
  private val bitmapPaint: Paint

  init {
    inputResolution = size
    val options = BitmapFactory.Options()
    options.inJustDecodeBounds = true
    BitmapFactory.decodeFile(path, options)
    options.inScaled = false
    options.inSampleSize = calculateInSampleSize(options, inputResolution.width, inputResolution.height)
    options.inPreferredConfig = Bitmap.Config.ARGB_8888
    options.inJustDecodeBounds = false
    options.inDither = false

    width = options.outWidth
    height = options.outHeight

    options.inJustDecodeBounds = false
    matrix = getRotatedMatrix(File(path), Matrix())
    paintFlagsDrawFilter = PaintFlagsDrawFilter(0, 2)

    bitmapPaint = Paint()
    bitmapPaint.isFilterBitmap = true

    Thread {
      originalBitmap = BitmapFactory.decodeFile(path)
      drawBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, width, height, matrix, true)
      bitmapCreateComplete()
    }.start()

  }

  override fun drawCanvas(canvas: Canvas) {
    val scale = getScale(canvas)

    canvas.save()
    canvas.scale(scale, scale, (canvas.width / 2).toFloat(), (canvas.height / 2).toFloat())
    canvas.drawFilter = paintFlagsDrawFilter
    if (drawBitmap?.isRecycled == true) {
      originalBitmap = BitmapFactory.decodeFile(path)
      drawBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, width, height, matrix, true)
    }
    drawBitmap?.let {
      canvas.drawBitmap(it, ((canvas.width - it.width) / 2).toFloat(), ((canvas.height - it.height) / 2).toFloat(), bitmapPaint)
    }
    canvas.restore()
  }

  override fun release() {
    if (drawBitmap?.isRecycled == false) {
      drawBitmap?.recycle()
    }
    if (originalBitmap?.isRecycled == false) {
      originalBitmap?.recycle()
    }

  }

  private fun getScale(canvas: Canvas): Float {
    drawBitmap?.let {
      if (canvas.width == canvas.height) {

        if (it.width <= it.height) {
          return canvas.width.toFloat() / it.width
        } else {
          return canvas.height.toFloat() / it.height
        }

      } else if (canvas.width > canvas.height) {
        return canvas.width.toFloat() / it.width
      } else {
        return canvas.height.toFloat() / it.height
      }

    }

    return 1f
  }

  private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    // Raw height and width of image
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
      if (width > height) {
        inSampleSize = Math.round(height.toFloat() / reqHeight.toFloat())
      } else {
        inSampleSize = Math.round(width.toFloat() / reqWidth.toFloat())
      }
    }

    if (inSampleSize <= 0) {
      inSampleSize = 1
    }

    return inSampleSize
  }

  private fun getRotatedMatrix(file: File, matrix: Matrix): Matrix {
    var exifInterface: ExifInterface? = null

    try {
      exifInterface = ExifInterface(file.path)
    } catch (e: IOException) {
      e.printStackTrace()
      return matrix
    }

    // 画像の向きを取得
    val exifOrientation = exifInterface.getAttributeInt(
      ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED
    )

    // 画像を回転させる処理をマトリクスに追加
    when (exifOrientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.setRotate(180f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.setRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.setRotate(-90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
    }// Do nothing.
    return matrix
  }

}