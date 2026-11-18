package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class DistanceEstimateTest {
    @Test fun distanceUsesRangeInsteadOfOneMisleadingNumber() {
        val estimate = estimateDistance(-78.0, referenceRssiAt1m = -59.0,
            spreadDb = 4.0, pathN = PathLossEnvironment.CROWDED.n)
        assertNotNull(estimate)
        assertTrue(estimate!!.lowerMeters < estimate.meters)
        assertTrue(estimate.upperMeters > estimate.meters)
        assertTrue(estimate.label.contains("~"))
    }

    @Test fun calibrationMovesTheEstimateCloser() {
        val uncalibrated = estimateDistance(-75.0, null, null, PathLossEnvironment.CLASSROOM.n)!!
        val calibrated = estimateDistance(-75.0, referenceRssiAt1m = -50.0, null, PathLossEnvironment.CLASSROOM.n)!!
        assertTrue(calibrated.meters > uncalibrated.meters)
    }

    @Test fun missingStrengthHasNoDistance() {
        assertNull(estimateDistance(null))
        assertNull(estimateDistance(Double.NaN))
    }
}
