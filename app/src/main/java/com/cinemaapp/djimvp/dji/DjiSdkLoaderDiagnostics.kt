package com.cinemaapp.djimvp.dji

object DjiSdkLoaderDiagnostics {
    @Volatile
    var helperInstallAttempted: Boolean = false

    @Volatile
    var helperInstallSucceeded: Boolean = false

    @Volatile
    var helperInstallError: String? = null

    @Volatile
    var helperInstallRawError: String? = null
}
