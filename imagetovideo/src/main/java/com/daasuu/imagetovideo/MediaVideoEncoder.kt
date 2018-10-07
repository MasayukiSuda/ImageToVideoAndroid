package com.daasuu.imagetovideo

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.util.Size
import android.view.Surface

class MediaVideoEncoder(val size: Size, muxer: MediaMuxerCaptureWrapper?, listener: MediaEncoderListener?) :
  MediaEncoder(muxer, listener) {

  companion object {
    private const val TAG = "MediaVideoEncoder"
    private const val MIME_TYPE = "video/avc"
    private const val FRAME_RATE = 30
    private const val BPP = 0.25f
  }

  var surface: Surface? = null


  override fun prepare() {
    trackIndex = -1
    isEOS = false
    muxerStarted = isEOS

    val videoCodecInfo = selectVideoCodec(MIME_TYPE)

    if (videoCodecInfo == null) {
      Log.e(TAG, "Unable to find an appropriate codec for $MIME_TYPE")
      return
    }
    Log.i(TAG, "selected codec: " + videoCodecInfo.name)

    val format = MediaFormat.createVideoFormat(MIME_TYPE, size.width, size.height)
    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    format.setInteger(MediaFormat.KEY_BIT_RATE, calcBitRate(size.width, size.height))
    format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
    Log.i(TAG, "format: $format")

    mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)

    mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    // get Surface for encoder input
    // this method only can call between #configure and #start
    surface = mediaCodec?.createInputSurface()
    mediaCodec?.start()
    Log.i(TAG, "prepare finishing")
    if (listener != null) {
      try {
        listener.onPrepared(this)
      } catch (e: Exception) {
        Log.e(TAG, "prepare:", e)
      }

    }
  }

  private fun calcBitRate(width: Int, height: Int): Int {
    val bitrate = (BPP * FRAME_RATE.toFloat() * width.toFloat() * height.toFloat()).toInt()
    Log.i(TAG, "bitrate=$bitrate")
    return bitrate
  }


  /**
   * select the first codec that match a specific MIME type
   *
   * @param mimeType
   * @return null if no codec matched
   */
  private fun selectVideoCodec(mimeType: String): MediaCodecInfo? {
    Log.v(TAG, "selectVideoCodec:")

    // get the list of available codecs
    val list = MediaCodecList(MediaCodecList.ALL_CODECS)
    val codecInfos = list.codecInfos

    val numCodecs = codecInfos.size
    for (i in 0 until numCodecs) {
      val codecInfo = codecInfos[i]

      if (!codecInfo.isEncoder) {    // skipp decoder
        continue
      }
      // select first codec that match a specific MIME type and color format
      val types = codecInfo.supportedTypes
      for (j in types.indices) {
        if (types[j].equals(mimeType, ignoreCase = true)) {
          Log.i(TAG, "codec:" + codecInfo.name + ",MIME=" + types[j])
          val format = selectColorFormat(codecInfo, mimeType)
          if (format > 0) {
            return codecInfo
          }
        }
      }
    }
    return null
  }

  /**
   * select color format available on specific codec and we can use.
   *
   * @return 0 if no colorFormat is matched
   */
  private fun selectColorFormat(codecInfo: MediaCodecInfo, mimeType: String): Int {
    Log.i(TAG, "selectColorFormat: ")
    var result = 0
    val caps: MediaCodecInfo.CodecCapabilities
    try {
      Thread.currentThread().priority = Thread.MAX_PRIORITY
      caps = codecInfo.getCapabilitiesForType(mimeType)
    } finally {
      Thread.currentThread().priority = Thread.NORM_PRIORITY
    }
    var colorFormat: Int
    for (i in caps.colorFormats.indices) {
      colorFormat = caps.colorFormats[i]
      if (isRecognizedViewoFormat(colorFormat)) {
        if (result == 0)
          result = colorFormat
        break
      }
    }
    if (result == 0)
      Log.e(TAG, "couldn't find a good color format for " + codecInfo.name + " / " + mimeType)
    return result
  }

  private fun isRecognizedViewoFormat(colorFormat: Int): Boolean {
    Log.i(TAG, "isRecognizedViewoFormat:colorFormat=$colorFormat")
    return colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
  }

  override fun signalEndOfInputStream() {
    Log.d(TAG, "sending EOS to encoder")
    mediaCodec?.signalEndOfInputStream()    // API >= 18
    isEOS = true
  }

}