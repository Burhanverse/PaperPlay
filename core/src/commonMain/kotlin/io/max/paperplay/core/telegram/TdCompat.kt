package io.max.paperplay.core.telegram

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// TDLib 1.8.60 and later changed a few request shapes: audio and document content nest their file in `inputAudio` / `inputDocument`,
// and a topic is `topic_id: messageTopicForum` instead of `message_thread_id`. A wrong shape fails ("InputFile is not specified")
// or, for a topic, is ignored and posts to General. The shape follows the version of the library that is loaded, since the phone and
// PC libraries are replaced separately. Same rule as the Flutter app (td_compat.dart).

/** Whether a TDLib [version] such as "1.8.70" uses the new request shapes. An unknown version is taken as old. */
fun usesNewTdApi(version: String?): Boolean {
    val parts = version.orEmpty().split('.')
    if (parts.size < 3) return false
    val minor = parts[1].toIntOrNull() ?: return false
    val patch = parts[2].toIntOrNull() ?: return false
    return minor > 8 || (minor == 8 && patch >= 60)
}

/** Puts the topic into a `sendMessage` request; nothing for General (no topic), which is what reaches General. */
fun JsonObjectBuilder.topicField(topicId: Long?, newApi: Boolean) {
    if (topicId == null || topicId <= 1L) return
    if (newApi) put("topic_id", buildJsonObject { put("@type", "messageTopicForum"); put("forum_topic_id", topicId) })
    else put("message_thread_id", topicId)
}

/** `inputMessageAudio` for [file] (an input file) with its tags and length (Telegram shows 0:00 without it). */
fun audioContent(file: JsonObject, newApi: Boolean, title: String, performer: String, durationSeconds: Int = 0): JsonObject = buildJsonObject {
    put("@type", "inputMessageAudio")
    if (newApi) {
        put("audio", buildJsonObject {
            put("@type", "inputAudio")
            put("audio", file)
            put("duration", durationSeconds)
            if (title.isNotEmpty()) put("title", title)
            if (performer.isNotEmpty()) put("performer", performer)
        })
    } else {
        put("audio", file)
        put("duration", durationSeconds)
        if (title.isNotEmpty()) put("title", title)
        if (performer.isNotEmpty()) put("performer", performer)
    }
}

/** `inputMessageDocument` that stays a plain file, with an optional caption. */
fun documentContent(file: JsonObject, newApi: Boolean, caption: JsonObject? = null): JsonObject = buildJsonObject {
    put("@type", "inputMessageDocument")
    if (newApi) put("document", buildJsonObject { put("@type", "inputDocument"); put("document", file); put("disable_content_type_detection", true) })
    else { put("document", file); put("disable_content_type_detection", true) }
    caption?.let { put("caption", it) }
}
