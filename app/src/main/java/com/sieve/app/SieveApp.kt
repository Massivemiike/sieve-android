package com.sieve.app

import android.app.Application
import com.sieve.app.di.AppGraph

class SieveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
