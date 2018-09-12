package com.daasuu.imagetovideo

import android.graphics.SurfaceTexture
import android.opengl.*
import android.util.Log
import android.util.Size
import android.view.Surface

internal class GLThread(
  private val surface: Surface,
  private val glImageOverlay: GLImageOverlay,
  private val size: Size
) : Thread() {

  companion object {
    private const val TAG = "GLThread"
    private const val EGL_RECORDABLE_ANDROID = 0x3142
  }

  private var threadFinish: Boolean = false

  private var eglDisplay: EGLDisplay? = EGL14.EGL_NO_DISPLAY
  private var eglContext: EGLContext? = EGL14.EGL_NO_CONTEXT
  private var eglSurface: EGLSurface? = EGL14.EGL_NO_SURFACE

  private val MVPMatrix = FloatArray(16)
  private val STMatrix = FloatArray(16)

  private lateinit var surfaceTexture: SurfaceTexture

  override fun run() {
    super.run()
    if (!initGL()) {
      Log.e(TAG, "Failed OpenGL initialize")
      threadFinish = true
    }

    glImageOverlay.setUpSurface()
    GLES20.glViewport(0, 0, size.width, size.height)
    surfaceTexture = SurfaceTexture(glImageOverlay.textureID)

    Matrix.setIdentityM(STMatrix, 0)

    while (!threadFinish) {
      drawImage()
    }
    release()
  }

  private fun initGL(): Boolean {
    eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
      throw RuntimeException("unable to get EGL14 display")
    }
    val version = IntArray(2)
    if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
      eglDisplay = null
      throw RuntimeException("unable to initialize EGL14")
    }
    // Configure EGL for recordable and OpenGL ES 2.0.  We want enough RGB bits
    // to minimize artifacts from possible YUV conversion.
    val attribList = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
    val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
    val numConfigs = IntArray(1)
    if (!EGL14.eglChooseConfig(
        eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0
      )) {
      throw RuntimeException("unable to find RGB888+recordable ES2 EGL config")
    }
    // Configure context for OpenGL ES 2.0.
    val attribList2 = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
    eglContext = EGL14.eglCreateContext(
      eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, attribList2, 0
    )
    checkEglError("eglCreateContext")
    if (eglContext == null) {
      throw RuntimeException("null context")
    }
    // Create a window surface, and attach it to the Surface we received.
    val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
    eglSurface = EGL14.eglCreateWindowSurface(
      eglDisplay, configs[0], surface, surfaceAttribs, 0
    )
    checkEglError("eglCreateWindowSurface")
    if (eglSurface == null) {
      throw RuntimeException("surface was null")
    }

    if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      throw RuntimeException("eglMakeCurrent failed")
    }

    return true

  }

  private fun drawImage() {
    Matrix.setIdentityM(MVPMatrix, 0)
    Matrix.scaleM(MVPMatrix, 0, 1f, -1f, 1f)
    glImageOverlay.draw(surfaceTexture, STMatrix, MVPMatrix)

    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
  }

  fun requestExitAndWait() {
    synchronized(this) {
      threadFinish = true
    }
    try {
      join()
    } catch (e: InterruptedException) {
      Log.e(TAG, e.message, e)
      Thread.currentThread().interrupt()
    }
  }

  private fun release() {
    if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
      EGL14.eglDestroySurface(eglDisplay, eglSurface)
      EGL14.eglDestroyContext(eglDisplay, eglContext)
      EGL14.eglReleaseThread()
      EGL14.eglTerminate(eglDisplay)
    }
    surface.release()
    eglDisplay = EGL14.EGL_NO_DISPLAY
    eglContext = EGL14.EGL_NO_CONTEXT
    eglSurface = EGL14.EGL_NO_SURFACE
    glImageOverlay.release()

  }

  /**
   * Checks for EGL errors.
   */
  private fun checkEglError(msg: String) {
    if (EGL14.eglGetError() != EGL14.EGL_SUCCESS) {
      throw RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(EGL14.eglGetError()))
    }
  }

}