package com.example.pavementdetection

import android.app.Application
import com.amap.api.maps.MapsInitializer

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 隐私合规：同意隐私政策后才能初始化
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        MapsInitializer.initialize(this)
    }
}