package io.max.paperplay.core.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration

/**
 * Runs [send], which returns the pending message, and waits for Telegram to confirm it; returns the confirmed message. TDLib answers
 * sendMessage and forwardMessages before anything was sent, so only this says a post exists. Throws [TdError] when Telegram refuses the
 * post or does not confirm it within [timeout].
 */
suspend fun TdClient.sendAndWait(timeout: Duration, send: suspend () -> JsonObject): JsonObject = coroutineScope {
    val sentId = CompletableDeferred<Long>()
    // Listens before the request goes out, so a quick confirmation is not missed.
    val outcome = async(start = CoroutineStart.UNDISPATCHED) {
        updates.first { u ->
            val t = (u["@type"] as? JsonPrimitive)?.contentOrNull
            (t == "updateMessageSendSucceeded" || t == "updateMessageSendFailed") && (u["old_message_id"] as? JsonPrimitive)?.longOrNull == sentId.await()
        }
    }
    try {
        val pending = send()
        sentId.complete((pending["id"] as? JsonPrimitive)?.longOrNull ?: throw TdError(0, "Telegram did not accept the post"))
        val done = withTimeoutOrNull(timeout) { outcome.await() } ?: throw TdError(408, "Telegram did not confirm the post in time")
        if ((done["@type"] as? JsonPrimitive)?.contentOrNull == "updateMessageSendFailed") {
            val err = done["error"] as? JsonObject
            throw TdError((err?.get("code") as? JsonPrimitive)?.longOrNull?.toInt() ?: 0, (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Telegram refused the post")
        }
        done["message"] as? JsonObject ?: throw TdError(0, "The new post was not confirmed")
    } finally {
        outcome.cancel()
    }
}
