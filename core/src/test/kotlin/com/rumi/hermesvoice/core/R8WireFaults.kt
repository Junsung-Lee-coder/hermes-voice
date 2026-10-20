package com.rumi.hermesvoice.core

import org.json.JSONArray
import org.json.JSONObject

/** Synthetic malformed JSON-RPC replies only; not a claim about an installed server. */
object R8WireFaults {
    fun mutate(frame: JSONObject, kind: String) {
        when (kind) {
            "unknown" -> frame.getJSONObject("result").put("status", "future-status")
            "empty" -> frame.getJSONObject("result").put("status", "")
            "missingStatus" -> frame.getJSONObject("result").remove("status")
            "nullStatus" -> frame.getJSONObject("result").put("status", JSONObject.NULL)
            "numberStatus" -> frame.getJSONObject("result").put("status", 1)
            "objectStatus" -> frame.getJSONObject("result").put("status", JSONObject().put("status", "streaming"))
            "arrayStatus" -> frame.getJSONObject("result").put("status", JSONArray().put("streaming"))
            "missingResult" -> { frame.remove("result"); frame.put("error", JSONObject.NULL) }
            "nullResult" -> frame.put("result", JSONObject.NULL)
            "stringResult" -> frame.put("result", "streaming")
            "arrayResult" -> frame.put("result", JSONArray().put("streaming"))
            "numberResult" -> frame.put("result", 1)
            "malformedError" -> frame.put("error", "not an error object")
            else -> {
                frame.remove("result")
                val error = JSONObject().put("code", -32603).put("message", "synthetic post-effect fault")
                when (kind) {
                    "rpcGeneric" -> Unit
                    "rpcMissingReason" -> error.put("code", 4090)
                    "rpcWrongReason" -> error.put("code", 4090).put("data", JSONObject().put("reason", "OTHER"))
                    "rpcPrefix" -> error.put("code", 4090).put("data", JSONObject().put("reason", "SESSION_NOT_OWNED_EXTRA"))
                    "rpcWrongCode" -> error.put("data", JSONObject().put("reason", "SESSION_NOT_OWNED"))
                    "rpcStringCode" -> error.put("code", "4090").put("data", JSONObject().put("reason", "SESSION_NOT_OWNED"))
                    else -> error("unknown fault $kind")
                }
                frame.put("error", error)
            }
        }
    }
}
