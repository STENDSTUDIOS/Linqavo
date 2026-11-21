package io.relay.app.core

/** Android 17's permission does not exist on older releases. */
object LocalNetworkPermission {
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"
    const val MIN_API = 37

    fun canShare(sdk: Int, granted: Boolean): Boolean = sdk < MIN_API || granted
}
