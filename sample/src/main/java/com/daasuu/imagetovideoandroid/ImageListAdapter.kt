package com.daasuu.imagetovideoandroid

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide

internal class ImageListAdapter(context: Context, resource: Int, objects: List<String>) : ArrayAdapter<String>(context, resource, objects) {

  private val layoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

  override fun getView(position: Int, view: View?, parent: ViewGroup): View {
    var convertView = view
    val path = getItem(position)

    if (null == convertView) {
      convertView = layoutInflater.inflate(R.layout.row_image_list, null)
    }

    convertView?.let {
      val imageView = it.findViewById<ImageView>(R.id.image)
      val textView = it.findViewById<TextView>(R.id.txt_image_name)

      textView.setText(path)

      Glide.with(context.applicationContext).load(path).into(imageView)
    }


    return convertView!!
  }

}
