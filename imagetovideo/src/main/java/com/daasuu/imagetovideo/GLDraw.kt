package com.daasuu.imagetovideo

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*

internal open class GLDraw(
  private val vertexShaderSource: String,
  private val fragmentShaderSource: String
) {

  companion object {
    private const val FLOAT_SIZE_BYTES = 4
    private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
    private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 0
    private const val TRIANGLE_VERTICES_DATA_UV_OFFSET = 3
  }

  private val triangleVerticesData = floatArrayOf(
    // X, Y, Z, U, V
    -1.0f, -1.0f, 0f, 0f, 0f, 1.0f, -1.0f, 0f, 1f, 0f, -1.0f, 1.0f, 0f, 0f, 1f, 1.0f, 1.0f, 0f, 1f, 1f
  )

  private var triangleVertices: FloatBuffer

  private var program: Int = 0
  var textureID = -12345
  protected var clearColor = floatArrayOf(0f, 0f, 0f, 1f)

  private val handleMap = HashMap<String, Int>()

  init {
    triangleVertices = ByteBuffer.allocateDirect(
      triangleVerticesData.size * FLOAT_SIZE_BYTES
    ).order(ByteOrder.nativeOrder()).asFloatBuffer()
    triangleVertices.put(triangleVerticesData).position(0)
  }

  fun draw(surfaceTexture: SurfaceTexture, STMatrix: FloatArray, MVPMatrix: FloatArray) {
    GLHelper.checkGlError("onDrawFrame start")


    GLES20.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
    GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
    GLES20.glUseProgram(program)
    GLHelper.checkGlError("glUseProgram")

    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID)
    triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
    GLES20.glVertexAttribPointer(
      getHandle("aPosition"), 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices
    )
    GLES20.glEnableVertexAttribArray(getHandle("aPosition"))

    triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET)
    GLES20.glVertexAttribPointer(
      getHandle("aTextureCoord"), 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices
    )
    GLHelper.checkGlError("glVertexAttribPointer aTextureHandle")

    GLES20.glEnableVertexAttribArray(getHandle("aTextureCoord"))
    GLHelper.checkGlError("glEnableVertexAttribArray aTextureHandle")

    surfaceTexture.getTransformMatrix(STMatrix)

    GLES20.glUniformMatrix4fv(getHandle("uMVPMatrix"), 1, false, MVPMatrix, 0)
    GLES20.glUniformMatrix4fv(getHandle("uSTMatrix"), 1, false, STMatrix, 0)


    onDraw()


    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLHelper.checkGlError("glDrawArrays")

    GLES20.glFinish()
  }

  open fun onDraw() {}

  open fun setUpSurface() {
    val vertexShader = GLHelper.loadShader(vertexShaderSource, GLES20.GL_VERTEX_SHADER)
    val fragmentShader = GLHelper.loadShader(fragmentShaderSource, GLES20.GL_FRAGMENT_SHADER)
    program = GLHelper.createProgram(vertexShader, fragmentShader)
    if (program == 0) {
      throw RuntimeException("failed creating program")
    }

    getHandle("aPosition")
    getHandle("aTextureCoord")
    getHandle("uMVPMatrix")
    getHandle("uSTMatrix")

    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    textureID = textures[0]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureID)
    GLHelper.checkGlError("glBindTexture textureID")
    GLES20.glTexParameterf(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat()
    )
    GLES20.glTexParameterf(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat()
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
    )
    GLES20.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
    )
    GLHelper.checkGlError("glTexParameter")
  }

  protected fun getHandle(name: String): Int {
    val value = handleMap[name]
    if (value != null) {
      return value
    }

    var location = GLES20.glGetAttribLocation(program, name)
    if (location == -1) {
      location = GLES20.glGetUniformLocation(program, name)
    }
    if (location == -1) {
      throw IllegalStateException("Could not get attrib or uniform location for $name")
    }
    handleMap[name] = location
    return location
  }

  open fun release() {}

  fun setClearColor(
    red: Float, green: Float, blue: Float, alpha: Float
  ) {
    this.clearColor = floatArrayOf(red, green, blue, alpha)
  }

}