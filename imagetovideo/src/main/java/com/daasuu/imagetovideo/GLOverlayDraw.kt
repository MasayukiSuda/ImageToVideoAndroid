package com.daasuu.imagetovideo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Size

internal open class GLOverlayDraw : GLDraw(GLHelper.DEFAULT_VERTEX_SHADER, GLHelper.OVERLAY_FRAGMENT_SHADER) {
  private val textures = IntArray(1)

  private var bitmap: Bitmap? = null

  var inputResolution = Size(720, 720)

  private fun createBitmap() {
    if (bitmap == null || bitmap!!.width != inputResolution.width || bitmap!!.height != inputResolution.height) {
      // BitmapUtil.releaseBitmap(bitmap);
      bitmap = Bitmap.createBitmap(inputResolution.width, inputResolution.height, Bitmap.Config.ARGB_8888)
    }
  }

  override fun setUpSurface() {
    super.setUpSurface()// 1
    GLES20.glGenTextures(1, textures, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])

    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

    getHandle("oTexture")
    createBitmap()
  }

  override fun onDraw() {
    createBitmap()

    bitmap?.let {
      it.eraseColor(Color.argb(0, 0, 0, 0))
      val bitmapCanvas = Canvas(it)
      drawCanvas(bitmapCanvas)
    }

    val offsetDepthMapTextureUniform = getHandle("oTexture")// 3

    GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])

    if (bitmap != null && !bitmap!!.isRecycled) {
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bitmap, 0)
    }

    GLES20.glUniform1i(offsetDepthMapTextureUniform, 3)
  }

  open fun drawCanvas(canvas: Canvas) {}

}