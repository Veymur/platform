package com.veymur.recorder.core.localdata

import android.location.Location

/** Snapshot on the callback thread, before enqueueing. Android's absent optional values are zeros. */
fun locationObservationInput(location: Location) = GpsInput(
    location.latitude, location.longitude,
    location.accuracy.toDouble().takeIf { location.hasAccuracy() },
    location.altitude.takeIf { location.hasAltitude() },
    location.speed.toDouble().takeIf { location.hasSpeed() },
    location.time, location.elapsedRealtimeNanos, location.isMock
)
