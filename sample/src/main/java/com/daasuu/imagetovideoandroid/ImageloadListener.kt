package com.daasuu.imagetovideoandroid

interface ImageLoadListener {

  fun onVideoLoaded(imagePath: List<String>)

  fun onFailed(e: Exception)
}
