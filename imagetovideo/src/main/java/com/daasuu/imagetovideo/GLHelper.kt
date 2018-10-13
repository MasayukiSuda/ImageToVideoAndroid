package com.daasuu.imagetovideo

import android.opengl.GLES20
import android.opengl.GLES20.GL_TRUE
import android.util.Log

internal object GLHelper {

  fun createProgram(vertexShader: Int, pixelShader: Int): Int {
    val program = GLES20.glCreateProgram()
    if (program == 0) {
      throw RuntimeException("Could not create program")
    }

    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, pixelShader)

    GLES20.glLinkProgram(program)
    val linkStatus = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
    if (linkStatus[0] != GL_TRUE) {
      GLES20.glDeleteProgram(program)
      throw RuntimeException("Could not link program")
    }
    return program
  }

  fun loadShader(strSource: String, iType: Int): Int {
    val compiled = IntArray(1)
    val iShader = GLES20.glCreateShader(iType)
    GLES20.glShaderSource(iShader, strSource)
    GLES20.glCompileShader(iShader)
    GLES20.glGetShaderiv(iShader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
      Log.d("Load Shader Failed", "Compilation\n" + GLES20.glGetShaderInfoLog(iShader))
      return 0
    }
    return iShader
  }

  fun checkGlError(op: String) {
    var error = GLES20.glGetError()
    while (error != GLES20.GL_NO_ERROR) {
      Log.w("OpenGL", "$op: glError $error")
      error = GLES20.glGetError()
    }
  }

  const val DEFAULT_VERTEX_SHADER =
    "uniform mat4 uMVPMatrix;\n" +
      "uniform mat4 uSTMatrix;\n" +
      "attribute vec4 aPosition;\n" +
      "attribute vec4 aTextureCoord;\n" +
      "varying vec2 vTextureCoord;\n" +
      "void main() {\n" +
      "  gl_Position = uMVPMatrix * aPosition;\n" +
      "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
      "}\n"

  const val DEFAULT_FRAGMENT_SHADER =
    "#extension GL_OES_EGL_image_external : require\n" +
      "precision mediump float;\n" +      // highp here doesn't seem to matter
      "varying vec2 vTextureCoord;\n" +
      "uniform samplerExternalOES sTexture;\n" +
      "void main() {\n" +
      "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
      "}\n"

  const val OVERLAY_FRAGMENT_SHADER =
    "#extension GL_OES_EGL_image_external : require\n" +
      "precision mediump float;\n" +
      "varying vec2 vTextureCoord;\n" +
      "uniform samplerExternalOES sTexture;\n" +
      "uniform lowp sampler2D oTexture;\n" +
      "void main() {\n" +
      "     lowp vec4 c2 = texture2D(sTexture, vTextureCoord);\n" +
      "     lowp vec4 c1 = texture2D(oTexture, vTextureCoord);\n" +
      "     lowp vec4 outputColor;\n" +
      "     outputColor.r = c1.r + c2.r * c2.a * (1.0 - c1.a);\n" +
      "     outputColor.g = c1.g + c2.g * c2.a * (1.0 - c1.a);\n" +
      "     outputColor.b = c1.b + c2.b * c2.a * (1.0 - c1.a);\n" +
      "     outputColor.a = c1.a + c2.a * (1.0 - c1.a);\n" +
      "     gl_FragColor = outputColor;\n" +
      "}\n"

}