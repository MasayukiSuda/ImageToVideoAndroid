package com.daasuu.imagetovideo

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size

internal class ImageToVideoImpl(
  path: String,
  private val size: Size,
  private val duration: Long,
  private val listener: EncodeListener?,
  private val drawer: GLImageOverlay,
  private val completeListener: () -> Unit
) {

  private val mediaCodec: MediaCodec = MediaCodec.createEncoderByType("video/avc")
  private val muxer: MediaMuxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

  private var glThread: GLThread? = null
  private var muxerStarted: Boolean = false

  private var start = 0L
  private var now = 0L
  private var base = 0.0
  private var firstPresentationTimeUs = 0L

  private val mainHandler = Handler(Looper.getMainLooper())

  companion object {
    private const val FPS = 30
  }

  init {

    val format = MediaFormat.createVideoFormat("video/avc", size.width, size.height)
      .apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, calcBitRate(size.width, size.height))
        setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
      }

    mediaCodec.setCallback(object : MediaCodec.Callback() {
      override fun onOutputBufferAvailable(codec: MediaCodec?, index: Int, info: MediaCodec.BufferInfo?) {
        if (info == null || codec == null) return

        if (start == 0L) {
          start = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()

        val outputBuffer = codec.getOutputBuffer(index)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
          info.size = 0
        }

        if (info.size != 0) {
          if (!muxerStarted) {
            throw RuntimeException()
          }
          outputBuffer?.position(info.offset)
          outputBuffer?.limit(info.offset + info.size)

          if (firstPresentationTimeUs == 0L) {
            firstPresentationTimeUs = info.presentationTimeUs
          }

          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            finishEncode()
          }

          if (now - start >= base) {
            base += 1000 / FPS
            muxer.writeSampleData(0, outputBuffer!!, info)
            mainHandler.post {
              listener?.onProgress((info.presentationTimeUs - firstPresentationTimeUs) / duration.toFloat())
            }
          }

        }
        codec.releaseOutputBuffer(index, false)

        if (duration <= (info.presentationTimeUs - firstPresentationTimeUs)) {
          stop()
          return
        }

      }

      override fun onOutputFormatChanged(codec: MediaCodec?, format: MediaFormat?) {
        if (muxerStarted) {
          throw RuntimeException()
        }
        muxer.addTrack(format!!)
        muxer.start()
        muxerStarted = true
      }

      override fun onInputBufferAvailable(codec: MediaCodec?, index: Int) {
        // do nothing
      }

      override fun onError(codec: MediaCodec?, e: MediaCodec.CodecException?) {
        e?.let {
          listener?.onFailed(it)
        }
      }
    })
    mainHandler.post {
      mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      glThread = GLThread(mediaCodec.createInputSurface(), drawer, size)
    }

  }

  fun stop() {
    mainHandler.post {
      glThread?.requestExitAndWait()
      release()
      completeListener()
    }
  }

  fun encode() {
    mainHandler.post {
      glThread?.start()
      mediaCodec.start()
    }
  }

  fun finishEncode() {
    mediaCodec.signalEndOfInputStream()
  }

  fun release() {
    mediaCodec.stop()
    mediaCodec.release()
    muxer.stop()
    muxer.release()
  }

  private fun calcBitRate(width: Int, height: Int): Int {
    val bitrate = (0.25 * 30.0 * width.toDouble() * height.toDouble()).toInt()
    Log.i("calcBitRate", "bitrate=$bitrate")
    return bitrate
  }

}