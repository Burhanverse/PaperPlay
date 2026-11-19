package io.max.paperplay.core.recs

actual fun localHourOf(epochMs: Long): Int =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).hour
