package com.android.mms.activities

import android.content.Intent
import android.os.Bundle
import com.goodwy.commons.R as CommonsR
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.overrideActivityTransition
import com.android.mms.R

open class SimpleActivity : BaseSimpleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyOpenFadeTransition()
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        applyOpenFadeTransition()
        super.onNewIntent(intent)
    }

    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        applyOpenFadeTransition()
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        super.startActivity(intent, options)
        applyOpenFadeTransition()
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        super.startActivityForResult(intent, requestCode)
        applyOpenFadeTransition()
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        super.startActivityForResult(intent, requestCode, options)
        applyOpenFadeTransition()
    }

    override fun finish() {
        super.finish()
        applyCloseFadeTransition()
    }

    /** Only the opening screen fades in; the screen underneath stays still (avoids double cross-fade jank). */
    private fun applyOpenFadeTransition() {
        overrideActivityTransition(CommonsR.anim.fadein, 0)
    }

    /** Only the closing screen fades out; the screen underneath is already visible. */
    private fun applyCloseFadeTransition() {
        overrideActivityTransition(0, CommonsR.anim.fadeout, exiting = true)
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_one,
        R.mipmap.ic_launcher_two,
        R.mipmap.ic_launcher_three,
        R.mipmap.ic_launcher_four,
        R.mipmap.ic_launcher_five,
        R.mipmap.ic_launcher_six,
        R.mipmap.ic_launcher_seven,
        R.mipmap.ic_launcher_eight,
        R.mipmap.ic_launcher_nine,
        R.mipmap.ic_launcher_ten,
        R.mipmap.ic_launcher_eleven
    )

    override fun getAppLauncherName() = getString(R.string.messages)

    override fun getRepositoryName() = "Messages"
}
