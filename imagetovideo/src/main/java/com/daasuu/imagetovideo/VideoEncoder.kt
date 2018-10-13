package com.daasuu.imagetovideo

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.IOException
import java.lang.ref.WeakReference

internal class VideoEncoder(
  private val size: Size,
  muxer: MediaMuxerCaptureWrapper?,
  private val onCompleteListener: () -> Unit

) : Runnable {

  companion object {
    private const val MIME_TYPE = "video/avc"
    private const val FRAME_RATE = 30
    private const val BPP = 0.25f
    private const val TAG = "VideoEncoder"
    private const val TIMEOUT_USEC = 10000    // 10[msec]
  }

  lateinit var surface: Surface

  private val sync = java.lang.Object()
  /**
   * Flag that indicate this encoder is capturing now.
   */
  private var isCapturing: Boolean = false
  /**
   * Flag that indicate the frame data will be available soon.
   */
  private var requestDrain: Int = 0
  /**
   * Flag to request stopEncode capturing
   */
  private var requestStop: Boolean = false
  /**
   * Flag that indicate encoder received EOS(End Of Stream)
   */
  private var isEOS: Boolean = false
  /**
   * Flag the indicate the muxer is running
   */
  private var muxerStarted: Boolean = false
  /**
   * Track Number
   */
  private var trackIndex: Int = 0
  /**
   * MediaCodec instance for encoding
   */
  private var mediaCodec: MediaCodec? = null
  /**
   * Weak refarence of MediaMuxerWarapper instance
   */
  private val weakMuxer: WeakReference<MediaMuxerCaptureWrapper>?
  /**
   * BufferInfo instance for dequeuing
   */
  private var bufferInfo: MediaCodec.BufferInfo? = null

  /**
   * previous presentationTimeUs for writing
   */
  private var prevOutputPTSUs: Long = 0

  /**
   * get next encoding presentationTimeUs
   *
   * @return
   */
  // presentationTimeUs should be monotonic
  // otherwise muxer fail to write
  val ptsUs: Long
    get() {
      var result = System.nanoTime() / 1000L
      if (result < prevOutputPTSUs)
        result = prevOutputPTSUs - result + result
      return result
    }

  init {
    if (muxer == null) throw NullPointerException("MediaMuxerCaptureWrapper is null")
    weakMuxer = WeakReference<MediaMuxerCaptureWrapper>(muxer)
    muxer.addEncoder(this)
    synchronized(sync) {
      // create BufferInfo here for effectiveness(to reduce GC)
      bufferInfo = MediaCodec.BufferInfo()
      // wait for starting thread
      Thread(this, javaClass.simpleName).start()
      try {
        sync.wait()
      } catch (e: InterruptedException) {
      }

    }
  }

  /**
   * the method to indicate frame data is soon available or already available
   *
   * @return return true if encoder is ready to encod.
   */
  fun frameAvailableSoon(): Boolean {
    synchronized(sync) {
      if (!isCapturing || requestStop) {
        return false
      }
      requestDrain++
      sync.notifyAll()
    }
    return true
  }

  /**
   * encoding loop on private thread
   */
  override fun run() {
    //		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
    synchronized(sync) {
      requestStop = false
      requestDrain = 0
      sync.notify()
    }
    val isRunning = true
    var localRequestStop = false
    var localRequestDrain = false
    while (isRunning) {
      synchronized(sync) {
        localRequestStop = requestStop
        localRequestDrain = requestDrain > 0
        if (localRequestDrain)
          requestDrain--
      }
      if (localRequestStop) {
        drain()
        // request stopEncode recording
        signalEndOfInputStream()
        // process output data again for EOS signale
        drain()
        // release all related objects
        release()
        break
      }
      if (localRequestDrain) {
        drain()
      } else {
        var error = false
        synchronized(sync) {
          try {
            sync.wait()
          } catch (e: InterruptedException) {
            error = true
          }

        }
        if (error) {
          break
        }
      }
    } // end of while
    Log.d(TAG, "Encoder thread exiting")
    synchronized(sync) {
      requestStop = true
      isCapturing = false
    }
    onCompleteListener()
  }

  /*
     * prepareing method for each sub class
     * this method should be implemented in sub class, so set this as abstract method
     * @throws IOException
     */
  @Throws(IOException::class)
  fun prepare() {
    trackIndex = -1
    isEOS = false
    muxerStarted = isEOS

    val videoCodecInfo = selectVideoCodec(MIME_TYPE)

    if (videoCodecInfo == null) {
      Log.e(TAG, "Unable to find an appropriate codec for ${MIME_TYPE}")
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
    mediaCodec?.let {
      it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      // get Surface for encoder input
      // this method only can call between #configure and #start
      surface = it.createInputSurface()
      it.start()
      Log.i(TAG, "prepare finishing")

    }
  }

  fun startRecording() {
    Log.v(TAG, "startRecording")
    synchronized(sync) {
      isCapturing = true
      requestStop = false
      sync.notifyAll()
    }
  }

  /**
   * the method to request stopEncode encoding
   */
  fun stopRecording() {
    Log.v(TAG, "stopRecording")
    synchronized(sync) {
      if (!isCapturing || requestStop) {
        return
      }
      requestStop = true    // for rejecting newer frame
      sync.notifyAll()
      // We can not know when the encoding and writing finish.
      // so we return immediately after request to avoid delay of caller thread
    }
  }

  //********************************************************************************
  //********************************************************************************

  /**
   * Release all releated objects
   */
  protected fun release() {
    Log.d(TAG, "release:")

    isCapturing = false
    mediaCodec?.let {
      try {
        it.stop()
        it.release()
        mediaCodec = null
      } catch (e: Exception) {
        Log.e(TAG, "failed releasing MediaCodec", e)
      }
    }
    if (muxerStarted) {
      weakMuxer?.get()
        ?.let {
          try {
            it.stop()
          } catch (e: Exception) {
            Log.e(TAG, "failed stopping muxer", e)
          }
        }
    }
    bufferInfo = null
  }

  private fun signalEndOfInputStream() {
    Log.d(TAG, "sending EOS to encoder")
    mediaCodec?.signalEndOfInputStream()    // API >= 18
    isEOS = true
  }

  /**
   * drain encoded data and write them to muxer
   */
  private fun drain() {
    mediaCodec?.let {
      var encoderStatus: Int
      var count = 0
      val muxer = weakMuxer?.get()
      val buffer = bufferInfo
      if (muxer == null || buffer == null) {
        Log.w(TAG, "muxer or bufferInfo is unexpectedly null")
        return
      }
      LOOP@ while (isCapturing) {
        // get encoded data with maximum timeout duration of TIMEOUT_USEC(=10[msec])
        encoderStatus = it.dequeueOutputBuffer(buffer, TIMEOUT_USEC.toLong())
        if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
          // wait 5 counts(=TIMEOUT_USEC x 5 = 50msec) until data/EOS come
          if (!isEOS) {
            if (++count > 5)
              break@LOOP        // out of while
          }
        } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          Log.v(TAG, "INFO_OUTPUT_FORMAT_CHANGED")
          // this status indicate the output format of codec is changed
          // this should come only once before actual encoded data
          // but this status never come on Android4.3 or less
          // and in that case, you should treat when MediaCodec.BUFFER_FLAG_CODEC_CONFIG come.
          if (muxerStarted) {    // second time request is error
            throw RuntimeException("format changed twice")
          }
          // get output format from codec and pass them to muxer
          // getOutputFormat should be called after INFO_OUTPUT_FORMAT_CHANGED otherwise crash.
          val format = it.outputFormat // API >= 16
          trackIndex = muxer.addTrack(format)
          muxerStarted = true
          if (!muxer.start()) {
            // we should wait until muxer is ready
            synchronized(muxer) {
              while (!muxer.isStarted)
                try {
                  muxer.lock.wait(100)
                } catch (e: InterruptedException) {
                  break
                }

            }
          }
        } else if (encoderStatus < 0) {
          // unexpected status
          Log.w(TAG, "drain:unexpected result from encoder#dequeueOutputBuffer: $encoderStatus")
        } else {
          val encodedData = it.getOutputBuffer(encoderStatus)
            ?: // this never should come...may be a MediaCodec internal error
            throw RuntimeException("encoderOutputBuffer $encoderStatus was null")
          if (buffer.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // You shoud set output format to muxer here when you target Android4.3 or less
            // but MediaCodec#getOutputFormat can not call here(because INFO_OUTPUT_FORMAT_CHANGED don't come yet)
            // therefor we should expand and prepare output format from buffer data.
            // This sample is for API>=18(>=Android 4.3), just ignore this flag here
            Log.d(TAG, "drain:BUFFER_FLAG_CODEC_CONFIG")
            buffer.size = 0
          }

          if (buffer.size != 0) {
            // encoded data is ready, clear waiting counter
            count = 0
            if (!muxerStarted) {
              // muxer is not ready...this will prrograming failure.
              throw RuntimeException("drain:muxer hasn't started")
            }
            // write encoded data to muxer(need to adjust presentationTimeUs.
            buffer.presentationTimeUs = ptsUs
            muxer.writeSampleData(trackIndex, encodedData, buffer)
            prevOutputPTSUs = buffer.presentationTimeUs
          }
          // return buffer to encoder
          it.releaseOutputBuffer(encoderStatus, false)
          if (buffer.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            // when EOS come.
            isCapturing = false
            break      // out of while
          }
        }
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
      Thread.currentThread()
        .priority = Thread.MAX_PRIORITY
      caps = codecInfo.getCapabilitiesForType(mimeType)
    } finally {
      Thread.currentThread()
        .priority = Thread.NORM_PRIORITY
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
}

