package com.daasuu.imagetovideo

import android.util.Size
import java.util.concurrent.TimeUnit

class ImageToVideoCreator(
  outputPath: String,
  inputImagePath: String,
  private val listener: EncodeListener? = null,
  size: Size = Size(720, 720),
  duration: Long = TimeUnit.SECONDS.toMicros(4)
) {

  private val imageToVideoImpl: ImageToVideoImpl

  private var imageCreateFinish = false
  private var startCall = false

  init {
    val drawer = GLImageOverlay(inputImagePath, size) {
      imageCreateFinish = true
      startAction()
    }

    imageToVideoImpl = ImageToVideoImpl(outputPath, size, duration, listener, drawer) {
      listener?.onCompleted()
    }
  }

  fun start() {
    startCall = true
    startAction()
  }

  private fun startAction() {
    if (startCall && imageCreateFinish) {
      imageToVideoImpl.encode()
    }
  }

  fun stop() {
    imageToVideoImpl.stop()
  }

}