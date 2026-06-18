package com.jiangdg.utils

import android.os.Build

object BuildCheck {
    @JvmStatic
    fun isAndroid5(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    @JvmStatic
    fun isLollipop(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    @JvmStatic
    fun isMarshmallow(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
}
