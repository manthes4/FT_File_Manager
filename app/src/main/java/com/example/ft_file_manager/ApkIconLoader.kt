package com.example.ft_file_manager

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.collection.LruCache
import java.util.concurrent.Executors

object ApkIconLoader {

    private val executor = Executors.newSingleThreadExecutor()
    private val cache = LruCache<String, Drawable>(50)

    fun load(context: Context, path: String, imageView: ImageView) {

        cache.get(path)?.let {
            imageView.setImageDrawable(it)
            return
        }

        imageView.setImageResource(R.drawable.apk_document_24px)

        executor.execute {
            try {
                val pm = context.packageManager
                val packageInfo = pm.getPackageArchiveInfo(path, 0)

                val appInfo = packageInfo?.applicationInfo
                if (appInfo != null) {

                    appInfo.sourceDir = path
                    appInfo.publicSourceDir = path

                    val icon = pm.getApplicationIcon(appInfo)

                    cache.put(path, icon)

                    imageView.post {
                        imageView.setImageDrawable(icon)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}