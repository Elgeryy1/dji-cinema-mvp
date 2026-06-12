package com.cinemaapp.djimvp

import android.app.Application
import android.content.Context
import android.util.Log
import com.cinemaapp.djimvp.dji.DjiSdkLoaderDiagnostics

class CineCamApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        DjiSdkLoaderDiagnostics.helperInstallAttempted = true
        runCatching {
            val helperClass = Class.forName("com.cySdkyc.clx.Helper")
            Log.i("DJI_REGISTER", "CineCamApp Helper.install() start")
            helperClass.getMethod("install", Application::class.java).invoke(null, this)
            DjiSdkLoaderDiagnostics.helperInstallSucceeded = true
            DjiSdkLoaderDiagnostics.helperInstallError = null
            DjiSdkLoaderDiagnostics.helperInstallRawError = null
            Log.i("DJI_REGISTER", "CineCamApp Helper.install() finished")
        }.onFailure {
            DjiSdkLoaderDiagnostics.helperInstallSucceeded = false
            DjiSdkLoaderDiagnostics.helperInstallError = it.message
            DjiSdkLoaderDiagnostics.helperInstallRawError = it.toString()
            Log.e("DJI_REGISTER", "DJI Helper.install() fallo: ${it}", it)
        }
    }
}
