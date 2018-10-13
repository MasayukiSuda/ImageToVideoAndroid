package com.daasuu.imagetovideo

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer

internal class MediaMuxerCaptureWrapper @Throws(IOException::class)
constructor(filePath: String,
            private val duration: Long,
            private val listener: EncodeListener?,
            private val overDurationListener: () -> Unit
) {

  companion object {
    private const val TAG = "MediaMuxerWrapper"
  }

  val lock = java.lang.Object()
  private val mediaMuxer: MediaMuxer = MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  private var encoderCount: Int = 0
  private var startedCount: Int = 0
  private var startTimeUs: Long = 0
  @get:Synchronized
  var isStarted: Boolean = false
    private set
  private var videoEncoder: VideoEncoder? = null
  private var preventAudioPresentationTimeUs: Long = -1

  init {
    startedCount = 0
    encoderCount = startedCount
    isStarted = false

  }

  @Throws(IOException::class)
  fun prepare() {
    videoEncoder?.prepare()
  }

  fun startRecording() {
    videoEncoder?.startRecording()
  }

  fun stopRecording() {
    videoEncoder?.stopRecording()
    videoEncoder = null
  }

  //**********************************************************************
  //**********************************************************************

  /**
   * assign encoder to this calss. this is called from encoder.
   *
   * @param encoder instance of MediaVideoEncoder or MediaAudioEncoder
   */
  internal fun addEncoder(encoder: VideoEncoder) {
    encoderCount = 1
    videoEncoder = encoder
  }

  /**
   * request start recording from encoder
   *
   * @return true when muxer is ready to write
   */
  @Synchronized
  internal fun start(): Boolean {
    Log.v(TAG, "start:")
    startedCount++
    if (encoderCount > 0 && startedCount == encoderCount) {
      mediaMuxer.start()
      isStarted = true
      synchronized(lock) {
        lock.notifyAll()
      }
      Log.v(TAG, "MediaMuxer started:")
    }
    return isStarted
  }

  /**
   * request stopEncode recording from encoder when encoder received EOS
   */
  /*package*/
  @Synchronized
  internal fun stop() {
    Log.v(TAG, "stopEncode:startedCount=$startedCount")
    startedCount--
    if (encoderCount > 0 && startedCount <= 0) {
      mediaMuxer.stop()
      mediaMuxer.release()
      isStarted = false
      Log.v(TAG, "MediaMuxer stopped:")
    }
  }

  /**
   * assign encoder to muxer
   *
   * @param format
   * @return minus value indicate error
   */
  @Synchronized
  internal fun addTrack(format: MediaFormat): Int {
    if (isStarted) {
      throw IllegalStateException("muxer already started")
    }

    val trackIx = mediaMuxer.addTrack(format)
    Log.i(TAG, "addTrack:trackNum=$encoderCount,trackIx=$trackIx,format=$format")

    return trackIx
  }

  /**
   * write encoded data to muxer
   *
   * @param trackIndex
   * @param byteBuf
   * @param bufferInfo
   */
  /*package*/
  @Synchronized
  internal fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
    //bufferInfo.presentationTimeUs
    if (startedCount <= 0) return

    if (startTimeUs == 0L) {
      startTimeUs = bufferInfo.presentationTimeUs
    }

    if (preventAudioPresentationTimeUs < bufferInfo.presentationTimeUs) {
      mediaMuxer.writeSampleData(trackIndex, byteBuf, bufferInfo)
      preventAudioPresentationTimeUs = bufferInfo.presentationTimeUs

      val progress = preventAudioPresentationTimeUs - startTimeUs
      listener?.onProgress(progress / duration.toFloat())
      if (duration <= progress) {
        overDurationListener()
      }
    }

  }


}

