package com.sbro.emucorec.core

import android.content.Context

/** Loads the EmuCoreC JNI glue and initializes its separately packaged core. */
object NativeLibraryLoader {
    fun isNativeSessionInitialized(): Boolean = Ps3Core.initialized

    fun ensureLoaded(context: Context) {
        Ps3Runtime.ensureInitialized(context.applicationContext)
    }
}
