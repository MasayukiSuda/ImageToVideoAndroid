package com.daasuu.imagetovideoandroid

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ImageLoader(private val context: Context) {
  private var executorService: ExecutorService? = null

  fun loadDeviceVideos(listener: ImageLoadListener) {
    getExecutorService().execute(ImageLoader.ImageLoadRunnable(listener, context))
  }

  fun abortLoadVideos() {
    if (executorService != null) {
      executorService!!.shutdown()
      executorService = null
    }
  }

  private fun getExecutorService(): ExecutorService {
    if (executorService == null) {
      executorService = Executors.newSingleThreadExecutor()
    }
    return executorService!!
  }

  private class ImageLoadRunnable(private val listener: ImageLoadListener, private val context: Context) : Runnable {
    private val handler = Handler(Looper.getMainLooper())

    private val projection = arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED)

    override fun run() {
      val cursor = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, MediaStore.Images.Media.DATE_ADDED)

      if (cursor == null) {
        handler.post { listener.onFailed(NullPointerException()) }
        return
      }

      val temp = ArrayList<String>(cursor.count)

      if (cursor.moveToLast()) {
        do {
          val path = cursor.getString(cursor.getColumnIndex(projection[0])) ?: continue
          if (!path.endsWith("png") && !path.endsWith("PNG") && !path.endsWith("jpg") && !path.endsWith("JPEG") && !path.endsWith("JPG") && !path.endsWith("jpeg") && !path.endsWith("GIF") && !path.endsWith("gif") && !path.endsWith("webp") && !path.endsWith("WEBP")) {
            continue
          }

          var file: File? = File(path)
          if (file!!.exists()) {
            try {
              temp.add(path)
            } catch (e: Exception) {
              continue
            }

          }
          file = null

        } while (cursor.moveToPrevious())
      }
      cursor.close()

      handler.post { listener.onVideoLoaded(temp) }
    }
  }

  companion object {

    private val TAG = "VideoLoader"
  }

}