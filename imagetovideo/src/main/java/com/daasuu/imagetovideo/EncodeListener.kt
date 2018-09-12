package com.daasuu.imagetovideo

interface EncodeListener {
  /**
   * Called to notify progress.
   *
   * @param progress Progress in [0.0, 1.0] range, or negative value if progress is unknown.
   */
  fun onProgress(progress: Float)

  /**
   * Called when transcode completed.
   */
  fun onCompleted()

  fun onFailed(exception: Exception)

}