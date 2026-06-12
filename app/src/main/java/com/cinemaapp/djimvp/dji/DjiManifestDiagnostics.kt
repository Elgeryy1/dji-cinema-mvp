package com.cinemaapp.djimvp.dji

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

data class DjiManifestDiagnostics(
    val packageName: String,
    val hasKey: Boolean,
    val keyLength: Int,
    val keyLast4: String
)

fun readDjiManifestDiagnostics(context: Context): DjiManifestDiagnostics {
    val packageName = context.packageName
    val apiKey = runCatching {
        val appInfo = getApplicationInfoWithMetadata(context, packageName)
        appInfo.metaData?.getString("com.dji.sdk.API_KEY").orEmpty()
    }.onFailure {
        Log.e("DJI_MANIFEST", "No se pudo leer meta-data DJI package=$packageName error=${it}", it)
    }.getOrDefault("")

    val diagnostics = DjiManifestDiagnostics(
        packageName = packageName,
        hasKey = apiKey.isNotBlank(),
        keyLength = apiKey.length,
        keyLast4 = apiKey.takeLast(4)
    )
    Log.i(
        "DJI_MANIFEST",
        "package=${diagnostics.packageName} hasKey=${diagnostics.hasKey} keyLength=${diagnostics.keyLength} keyLast4=${diagnostics.keyLast4}"
    )
    return diagnostics
}

private fun getApplicationInfoWithMetadata(context: Context, packageName: String): ApplicationInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    }
}
