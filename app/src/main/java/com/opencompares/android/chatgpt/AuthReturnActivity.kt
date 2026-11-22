package com.opencompares.android.chatgpt

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.opencompares.android.MainActivity

/**
 * Opened by the "Return to Open Compares" link on the sign-in callback page.
 * Brings the existing app screen back to the front, closing the browser tab above it.
 */
class AuthReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        /** Uses the package name as the scheme, so debug and release builds never compete for it. */
        fun returnUri(context: Context): String = "${context.packageName}://auth-complete"
    }
}
