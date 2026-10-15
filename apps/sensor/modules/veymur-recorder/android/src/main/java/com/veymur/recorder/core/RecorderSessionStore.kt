package com.veymur.recorder.core

import org.json.JSONObject

/** Session transactions; SQLCipher in production, isolated memory in replay. */
interface RecorderSessionStore {
    fun begin(device: String, gps: Boolean, configuration: JSONObject? = null): String
    fun transition(id: String, event: String, state: String, activeMs: Long, payload: JSONObject = JSONObject())
    fun append(id: String, kind: String, payload: JSONObject)
}
