package com.veymur.recorder

import android.app.Activity
import com.veymur.recorder.core.RecorderError

fun requireRecorderActivity(activity: Activity?, visible: Boolean = false): Activity {
    if (activity == null || activity.isFinishing || activity.isDestroyed || (visible && !activity.hasWindowFocus()))
        throw RecorderError("NO_ACTIVITY", "Open the app before this action; retry when visible")
    return activity
}

/** A just-dismissed permission/dialog window may still own focus while the host remains resumed. */
fun requireRecorderActionActivity(activity: Activity?, hostVisible: Boolean?): Activity {
    val host = requireRecorderActivity(activity)
    if (hostVisible == false || (hostVisible == null && !host.hasWindowFocus()))
        throw RecorderError("NO_ACTIVITY", "Open the app before this action; retry when visible")
    return host
}
