package io.nekohasekai.sfa.bg

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log

/**
 * One-shot VPN consent for the verification service. Not used by production UI.
 */
class XrayVerificationPrepareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            startActivityForResult(prepare, REQ)
        } else {
            Log.i(TAG, "VPN permission already granted")
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "VPN prepare result=$resultCode")
        finish()
    }

    companion object {
        private const val TAG = "XrayVerification"
        private const val REQ = 1
    }
}
