package fxc.dev.ads_debug_kit

import android.app.Activity
import android.os.Bundle

class AdsDebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(AdsDebugWindowManager.createStandaloneContent(this) { finish() })
    }
}
