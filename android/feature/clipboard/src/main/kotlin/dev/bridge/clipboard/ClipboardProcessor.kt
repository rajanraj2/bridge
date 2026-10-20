package dev.bridge.clipboard

import com.google.protobuf.ByteString
import dev.bridge.protocol.v1.ClipboardUpdate
import dev.bridge.protocol.v1.Envelope
import dev.bridge.security.IdentityManager
import java.util.UUID

sealed class ClipboardRejectionReason {
    data object OriginIsSelf : ClipboardRejectionReason()
    data object EventAlreadyProcessed : ClipboardRejectionReason()
    data object ContentHashMatchesRecent : ClipboardRejectionReason()
    data object TextExceedsLimit : ClipboardRejectionReason()
    data object SyncDisabled : ClipboardRejectionReason()
    data object InvalidPayload : ClipboardRejectionReason()
}

interface ClipboardDestinationAdapter {
    fun setClipboardText(text: String)
}

class ClipboardProcessor(
    var policy: ClipboardPolicy = ClipboardPolicy(),
    private val cache: ClipboardCache = ClipboardCache(),
    private val destinationAdapter: ClipboardDestinationAdapter? = null
) {
    private var localEventCounter: Long = 0

    var syncedCount: Int = 0
        private set
    var echoBlockedCount: Int = 0
        private set

    /**
     * Handles local clipboard change. Returns Envelope to send to peer, or null if filtered.
     */
    @Synchronized
    fun handleLocalCopy(
        text: String,
        localDeviceId: String,
        sessionId: String
    ): Envelope? {
        if (!policy.enabled) return null
        if (text.length > policy.maxTextLength) return null

        val textBytes = text.toByteArray(Charsets.UTF_8)
        val shaBytes = IdentityManager.sha256(textBytes)
        val shaHex = shaBytes.joinToString("") { "%02x".format(it) }

        if (policy.preventEchoLoops && cache.isContentRecent(shaHex)) {
            echoBlockedCount++
            return null
        }

        localEventCounter++
        val eventId = "clip-" + UUID.randomUUID().toString().lowercase()
        val originEventId = "local-$localEventCounter"

        cache.recordEvent(eventId, shaHex)

        val update = ClipboardUpdate.newBuilder()
            .setEventId(eventId)
            .setOriginDeviceId(localDeviceId)
            .setOriginEventId(originEventId)
            .setContentType("text/plain")
            .setText(text)
            .setContentSha256(ByteString.copyFrom(shaBytes))
            .setCreatedAtUnixMs(System.currentTimeMillis())
            .build()

        val envelope = Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString().lowercase())
            .setSessionId(sessionId)
            .setSenderDeviceId(localDeviceId)
            .setSequenceNumber(localEventCounter)
            .setCreatedAtUnixMs(System.currentTimeMillis())
            .setProtocolMajor(1)
            .setProtocolMinor(0)
            .setClipboardUpdate(update)
            .build()

        syncedCount++
        return envelope
    }

    /**
     * Ingests a received ClipboardUpdate envelope from remote peer.
     * Returns Result with the applied text or rejection reason.
     */
    @Synchronized
    fun handleRemoteUpdate(
        envelope: Envelope,
        localDeviceId: String
    ): Result<String> {
        if (!policy.enabled) {
            return Result.failure(Exception(ClipboardRejectionReason.SyncDisabled.toString()))
        }

        if (!envelope.hasClipboardUpdate()) {
            return Result.failure(Exception(ClipboardRejectionReason.InvalidPayload.toString()))
        }

        val update = envelope.clipboardUpdate

        // 1. Origin is self
        if (update.originDeviceId == localDeviceId) {
            echoBlockedCount++
            return Result.failure(Exception(ClipboardRejectionReason.OriginIsSelf.toString()))
        }

        // 2. Already processed event
        if (cache.isEventProcessed(update.eventId)) {
            echoBlockedCount++
            return Result.failure(Exception(ClipboardRejectionReason.EventAlreadyProcessed.toString()))
        }

        // 3. Length check
        if (update.text.length > policy.maxTextLength) {
            return Result.failure(Exception(ClipboardRejectionReason.TextExceedsLimit.toString()))
        }

        val shaHex = update.contentSha256.toByteArray().joinToString("") { "%02x".format(it) }

        // 4. Content loop check
        if (policy.preventEchoLoops && cache.isContentRecent(shaHex)) {
            echoBlockedCount++
            return Result.failure(Exception(ClipboardRejectionReason.ContentHashMatchesRecent.toString()))
        }

        cache.recordEvent(update.eventId, shaHex)
        destinationAdapter?.setClipboardText(update.text)
        syncedCount++

        return Result.success(update.text)
    }
}
