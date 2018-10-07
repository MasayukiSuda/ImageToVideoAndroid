package com.daasuu.imagetovideo

import android.media.MediaCodec
import android.util.Log
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

abstract class MediaEncoder(muxer: MediaMuxerCaptureWrapper?, protected val listener: MediaEncoderListener?) : Runnable {

  protected val sync = java.lang.Object()
  /**
   * Flag that indicate this encoder is capturing now.
   */
  @Volatile
  protected var isCapturing: Boolean = false
  /**
   * Flag that indicate the frame data will be available soon.
   */
  protected var requestDrain: Int = 0
  /**
   * Flag to request stopEncode capturing
   */
  @Volatile
  protected var requestStop: Boolean = false
  /**
   * Flag that indicate encoder received EOS(End Of Stream)
   */
  protected var isEOS: Boolean = false
  /**
   * Flag the indicate the muxer is running
   */
  protected var muxerStarted: Boolean = false
  /**
   * Track Number
   */
  protected var trackIndex: Int = 0
  /**
   * MediaCodec instance for encoding
   */
  protected var mediaCodec: MediaCodec? = null
  /**
   * Weak refarence of MediaMuxerWarapper instance
   */
  protected val weakMuxer: WeakReference<MediaMuxerCaptureWrapper>?
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

  interface MediaEncoderListener {
    fun onPrepared(encoder: MediaEncoder)

    fun onStopped(encoder: MediaEncoder)
  }

  init {
//    if (listener == null) throw NullPointerException("MediaEncoderListener is null")
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
  }

  /*
     * prepareing method for each sub class
     * this method should be implemented in sub class, so set this as abstract method
     * @throws IOException
     */
  @Throws(IOException::class)
  abstract fun prepare()

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
    try {
      listener?.onStopped(this)
    } catch (e: Exception) {
      Log.e(TAG, "failed onStopped", e)
    }

    isCapturing = false
    if (mediaCodec != null) {
      try {
        mediaCodec!!.stop()
        mediaCodec!!.release()
        mediaCodec = null
      } catch (e: Exception) {
        Log.e(TAG, "failed releasing MediaCodec", e)
      }

    }
    if (muxerStarted) {
      val muxer = weakMuxer?.get()
      if (muxer != null) {
        try {
          muxer.stop()
        } catch (e: Exception) {
          Log.e(TAG, "failed stopping muxer", e)
        }

      }
    }
    bufferInfo = null
  }

  protected open fun signalEndOfInputStream() {
    Log.d(TAG, "sending EOS to encoder")
    // signalEndOfInputStream is only avairable for video encoding with surface
    // and equivalent sending a empty buffer with BUFFER_FLAG_END_OF_STREAM flag.
    encode(null, 0, ptsUs)
  }

  /**
   * Method to set byte array to the MediaCodec encoder
   *
   * @param buffer
   * @param length             　length of byte array, zero means EOS.
   * @param presentationTimeUs
   */
  protected fun encode(buffer: ByteBuffer?, length: Int, presentationTimeUs: Long) {
    if (!isCapturing) return
    while (isCapturing) {
      val inputBufferIndex = mediaCodec!!.dequeueInputBuffer(TIMEOUT_USEC.toLong())
      if (inputBufferIndex >= 0) {
        val inputBuffer = mediaCodec!!.getInputBuffer(inputBufferIndex)
        inputBuffer!!.clear()
        if (buffer != null) {
          inputBuffer.put(buffer)
        }

        if (length <= 0) {
          // send EOS
          isEOS = true
          Log.i(TAG, "send BUFFER_FLAG_END_OF_STREAM")
          mediaCodec!!.queueInputBuffer(inputBufferIndex, 0, 0,
            presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          break
        } else {
          mediaCodec!!.queueInputBuffer(inputBufferIndex, 0, length,
            presentationTimeUs, 0)
        }
        break
      }
    }
  }

  /**
   * drain encoded data and write them to muxer
   */
  private fun drain() {
    if (mediaCodec == null) return
    var encoderStatus: Int
    var count = 0
    val muxer = weakMuxer!!.get()
    if (muxer == null) {
      Log.w(TAG, "muxer is unexpectedly null")
      return
    }
    LOOP@ while (isCapturing) {
      // get encoded data with maximum timeout duration of TIMEOUT_USEC(=10[msec])
      encoderStatus = mediaCodec!!.dequeueOutputBuffer(bufferInfo!!, TIMEOUT_USEC.toLong())
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
        val format = mediaCodec!!.outputFormat // API >= 16
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
        val encodedData = mediaCodec!!.getOutputBuffer(encoderStatus)
          ?: // this never should come...may be a MediaCodec internal error
          throw RuntimeException("encoderOutputBuffer $encoderStatus was null")
        if (bufferInfo!!.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
          // You shoud set output format to muxer here when you target Android4.3 or less
          // but MediaCodec#getOutputFormat can not call here(because INFO_OUTPUT_FORMAT_CHANGED don't come yet)
          // therefor we should expand and prepare output format from buffer data.
          // This sample is for API>=18(>=Android 4.3), just ignore this flag here
          Log.d(TAG, "drain:BUFFER_FLAG_CODEC_CONFIG")
          bufferInfo!!.size = 0
        }

        if (bufferInfo!!.size != 0) {
          // encoded data is ready, clear waiting counter
          count = 0
          if (!muxerStarted) {
            // muxer is not ready...this will prrograming failure.
            throw RuntimeException("drain:muxer hasn't started")
          }
          // write encoded data to muxer(need to adjust presentationTimeUs.
          bufferInfo!!.presentationTimeUs = ptsUs
          muxer.writeSampleData(trackIndex, encodedData, bufferInfo!!)
          prevOutputPTSUs = bufferInfo!!.presentationTimeUs
        }
        // return buffer to encoder
        mediaCodec!!.releaseOutputBuffer(encoderStatus, false)
        if (bufferInfo!!.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
          // when EOS come.
          isCapturing = false
          break      // out of while
        }
      }
    }
  }

  companion object {
    private val TAG = "MediaEncoder"

    protected val TIMEOUT_USEC = 10000    // 10[msec]
  }
}

