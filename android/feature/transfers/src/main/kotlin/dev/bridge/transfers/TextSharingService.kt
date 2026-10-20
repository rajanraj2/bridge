package dev.bridge.transfers

import dev.bridge.protocol.v1.Envelope
import dev.bridge.protocol.v1.ShareText
import java.util.UUID

enum class TextSharingType(val mimeType: String) {
    PLAIN_TEXT("text/plain"),
    URI_LIST("text/uri-list")
}

interface TextSharingListener {
    fun onTextReceived(shareId: String, content: String, type: TextSharingType)
}

class TextSharingService {
    companion object {
        const val MAX_TEXT_LENGTH = 1_048_576 // 1 MiB
        const val MAX_URL_LENGTH = 8192
    }

    private val listeners = mutableListOf<TextSharingListener>()

    fun addListener(listener: TextSharingListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TextSharingListener) {
        listeners.remove(listener)
    }

    fun createShareEnvelope(
        content: String,
        isUrl: Boolean,
        senderDeviceId: String,
        sessionId: String
    ): Envelope {
        if (isUrl && content.length > MAX_URL_LENGTH) {
            throw IllegalArgumentException("URL exceeds limit of $MAX_URL_LENGTH characters")
        }
        if (content.length > MAX_TEXT_LENGTH) {
            throw IllegalArgumentException("Content exceeds limit of $MAX_TEXT_LENGTH characters")
        }

        val shareId = "share-" + UUID.randomUUID().toString().lowercase()
        val type = if (isUrl) TextSharingType.URI_LIST else TextSharingType.PLAIN_TEXT

        val share = ShareText.newBuilder()
            .setShareId(shareId)
            .setContentType(type.mimeType)
            .setContent(content)
            .build()

        return Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString().lowercase())
            .setSessionId(sessionId)
            .setSenderDeviceId(senderDeviceId)
            .setCreatedAtUnixMs(System.currentTimeMillis())
            .setProtocolMajor(1)
            .setProtocolMinor(0)
            .setShareText(share)
            .build()
    }

    fun handleIncomingShare(envelope: Envelope): Boolean {
        if (!envelope.hasShareText()) return false
        val share = envelope.shareText
        val type = if (share.contentType == TextSharingType.URI_LIST.mimeType) {
            TextSharingType.URI_LIST
        } else {
            TextSharingType.PLAIN_TEXT
        }

        listeners.forEach { it.onTextReceived(share.shareId, share.content, type) }
        return true
    }
}
