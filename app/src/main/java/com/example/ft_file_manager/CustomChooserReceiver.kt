package com.example.ft_file_manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName

class CustomChooserReceiver : BroadcastReceiver() {
    companion object {
        var onAppSelected: ((ComponentName) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val clickedComponent: ComponentName? = intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        clickedComponent?.let {
            onAppSelected?.invoke(it)
        }
    }
}