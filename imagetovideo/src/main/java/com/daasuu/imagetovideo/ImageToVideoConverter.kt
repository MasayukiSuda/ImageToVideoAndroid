package com.daasuu.imagetovideo

import android.util.Size
import java.util.concurrent.TimeUnit

class ImageToVideoConverter(
  outputPath: String,
  inputImagePath: String,
  private val listener: EncodeListener? = null,
  private val size: Size = Size(720, 720),
  duration: Long = TimeUnit.SECONDS.toMicros(4)
) {


  private var imageCreateFinish = false
  private var startCall = false


  private var glThread: GLThread? = null
  private var muxer: MediaMuxerCaptureWrapper? = null
  private val drawer = GLImageOverlay(inputImagePath, size) {
    imageCreateFinish = true
    startAction()
  }

  init {
    muxer = MediaMuxerCaptureWrapper(outputPath, duration, listener) {
      stop()
    }
  }

  fun start() {
    startCall = true
    startAction()
  }

  private fun startAction() {
    if (startCall && imageCreateFinish) {
      //imageToVideoImpl.startEncode()
      try {
        val encoder = VideoEncoder(size, muxer) { listener?.onCompleted() }
        muxer?.prepare()
        glThread = GLThread(encoder.surface, drawer, size) {
          encoder.frameAvailableSoon()
        }
        glThread?.start()
        muxer?.startRecording()
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  fun stop() {
    muxer?.stopRecording()
    muxer = null

    glThread?.requestExitAndWait()
    glThread = null

  }

}