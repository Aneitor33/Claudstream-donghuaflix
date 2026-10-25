package com.donghuaflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaFlixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaFlixProvider())
    }
}
