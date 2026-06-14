package com.example.studycapturehelper.data

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.example.studycapturehelper.domain.ThermalPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class AndroidThermalPolicy @Inject constructor(
    @ApplicationContext context: Context,
) : ThermalPolicy {
    private val _multiplier = MutableStateFlow(1)
    override val multiplier: StateFlow<Int> = _multiplier

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(PowerManager::class.java)
            powerManager.addThermalStatusListener { status ->
                _multiplier.value = when {
                    status >= PowerManager.THERMAL_STATUS_SEVERE -> 4
                    status >= PowerManager.THERMAL_STATUS_MODERATE -> 2
                    else -> 1
                }
            }
        }
    }
}
