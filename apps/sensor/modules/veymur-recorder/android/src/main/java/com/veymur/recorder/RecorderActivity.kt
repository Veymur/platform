package com.veymur.recorder

import android.app.Activity
import com.veymur.recorder.core.RecorderError

fun requireRecorderActivity(activity: Activity?, visible: Boolean = false): Activity {
    if (activity == null || activity.isFinishing || activity.isDestroyed || (visible && !activity.hasWindowFocus()))
        throw RecorderError("NO_ACTIVITY", "Open the app before this action; retry when visible")
    return activity
}
