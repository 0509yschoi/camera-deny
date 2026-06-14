package com.example.studycapturehelper

import android.app.Application
import com.example.studycapturehelper.worker.MaintenanceScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StudyCaptureApp : Application() {
    @Inject lateinit var maintenanceScheduler: MaintenanceScheduler

    override fun onCreate() {
        super.onCreate()
        maintenanceScheduler.schedule()
    }
}
