package com.classroom.rfdetector.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 자동 감도 규칙을 센서 없이 검증한다. */
class SensitivityChoiceTest {
    private fun power(charging: Boolean = false, capacity: Int = 80, hotOrSaving: Boolean = false) =
        PowerState(charging = charging, capacityPercent = capacity, hotOrSaving = hotOrSaving)

    @Test fun maxPreferenceAlwaysMax() {
        assertEquals(SensitivityMode.MAX, chooseSensitivity(SensitivityPreference.MAX, power(capacity = 5, hotOrSaving = true)))
    }

    @Test fun saverPreferenceAlwaysStandard() {
        assertEquals(SensitivityMode.STANDARD, chooseSensitivity(SensitivityPreference.SAVER, power(charging = true, capacity = 100)))
    }

    @Test fun autoUsesMaxWhenHealthy() {
        assertEquals(SensitivityMode.MAX, chooseSensitivity(SensitivityPreference.AUTO, power(capacity = 55)))
    }

    @Test fun autoUsesSaverWhenLowOnBattery() {
        assertEquals(SensitivityMode.STANDARD, chooseSensitivity(SensitivityPreference.AUTO, power(capacity = 20)))
    }

    @Test fun autoKeepsMaxWhileChargingEvenWhenLow() {
        assertEquals(SensitivityMode.MAX, chooseSensitivity(SensitivityPreference.AUTO, power(charging = true, capacity = 10)))
    }

    @Test fun autoUsesSaverWhenHotOrSaving() {
        assertEquals(SensitivityMode.STANDARD, chooseSensitivity(SensitivityPreference.AUTO, power(capacity = 90, hotOrSaving = true)))
    }

    @Test fun autoUsesMaxWhenCapacityUnknown() {
        assertEquals(SensitivityMode.MAX, chooseSensitivity(SensitivityPreference.AUTO, power(capacity = -1)))
    }

    @Test fun maxModePullsScanIntervalWhenThrottlingIsOff() {
        assertEquals(10_000L, wifiScanPeriodMs(SensitivityMode.MAX, throttled = false))
        assertEquals(22_000L, wifiScanPeriodMs(SensitivityMode.MAX, throttled = true))
    }

    @Test fun saverModeKeepsGentleScanInterval() {
        assertEquals(20_000L, wifiScanPeriodMs(SensitivityMode.STANDARD, throttled = false))
        assertEquals(30_000L, wifiScanPeriodMs(SensitivityMode.STANDARD, throttled = true))
    }
}