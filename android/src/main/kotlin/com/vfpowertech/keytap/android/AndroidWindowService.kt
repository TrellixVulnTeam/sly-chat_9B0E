package com.vfpowertech.keytap.android

import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.vfpowertech.keytap.services.ui.UIWindowService

class AndroidWindowService(private val context: Context) : UIWindowService {
    override fun minimize() {
        val androidApp = AndroidApp.get(context)

        val activity = androidApp.currentActivity ?: return
        activity.moveTaskToBack(true)
    }

    override fun closeSoftKeyboard() {
        val androidApp = AndroidApp.get(context)

        val currentFocus = androidApp.currentActivity?.currentFocus

        if(currentFocus != null) {
            val inputManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputManager.hideSoftInputFromWindow(currentFocus.windowToken, 0)
        }
    }
}