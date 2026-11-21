package io.relay.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.relay.app.core.LocalNetworkPermission

object LocalNetworkAccess {
    fun granted(context: Context): Boolean = LocalNetworkPermission.canShare(
        Build.VERSION.SDK_INT,
        context.checkSelfPermission(LocalNetworkPermission.NAME) == PackageManager.PERMISSION_GRANTED,
    )
}
