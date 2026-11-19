package com.localmodels.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

object DeviceFiles {
    fun hasPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30)
        Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun create(context: Context): SharedFileTools {
        val roots = linkedMapOf("primary" to Environment.getExternalStorageDirectory())
        for (dir in context.getExternalFilesDirs(null).filterNotNull()) {
            val mount = File(dir.absolutePath.substringBefore("/Android/"))
            if (roots.values.none { it.canonicalFile == mount.canonicalFile }) roots["sd${roots.size}"] = mount
        }
        return SharedFileTools(roots) { Prefs(context).filesEnabled && hasPermission(context) }
    }
}
