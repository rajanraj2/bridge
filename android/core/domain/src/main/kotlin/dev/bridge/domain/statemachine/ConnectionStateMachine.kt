package dev.bridge.domain.statemachine

import dev.bridge.domain.model.ConnectionState
import dev.bridge.domain.model.DeviceIdentity
import dev.bridge.domain.model.PeerSession
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

sealed class ConnectionEvent {
    data object StartDiscovery : ConnectionEvent()
    data class PeerDiscovered(val peer: DeviceIdentity) : ConnectionEvent()
    data class InitiatePairing(val peer: DeviceIdentity) : ConnectionEvent()
    data object PairingSucceeded : ConnectionEvent()
    data class PairingFailed(val reason: String) : ConnectionEvent()
    data class ConnectToPeer(val peer: DeviceIdentity) : ConnectionEvent()
    data class ConnectionEstablished(val session: PeerSession) : ConnectionEvent()
    data class ConnectionLost(val temporary: Boolean) : ConnectionEvent()
    data object RetryBudgetExhausted : ConnectionEvent()
    data object Disconnect : ConnectionEvent()
}

class ConnectionStateMachine(
    val maxReconnectAttempts: Int = 5,
    val baseReconnectDelaySeconds: Double = 1.0
) {
    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    var currentPeer: DeviceIdentity? = null
        private set

    var currentSession: PeerSession? = null
        private set

    var reconnectAttempts: Int = 0
        private set

    @Synchronized
    fun handle(event: ConnectionEvent): ConnectionState {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                if (event is ConnectionEvent.StartDiscovery) {
                    state = ConnectionState.DISCOVERING
                }
            }

            ConnectionState.DISCOVERING -> {
                when (event) {
                    is ConnectionEvent.InitiatePairing -> {
                        currentPeer = event.peer
                        state = ConnectionState.PAIRING
                    }
                    is ConnectionEvent.ConnectToPeer -> {
                        currentPeer = event.peer
                        state = ConnectionState.CONNECTING
                    }
                    is ConnectionEvent.Disconnect -> {
                        resetToDisconnected()
                    }
                    else -> Unit
                }
            }

            ConnectionState.PAIRING -> {
                when (event) {
                    is ConnectionEvent.PairingSucceeded -> {
                        state = ConnectionState.CONNECTING
                    }
                    is ConnectionEvent.PairingFailed -> {
                        resetToDisconnected()
                    }
                    is ConnectionEvent.Disconnect -> {
                        resetToDisconnected()
                    }
                    else -> Unit
                }
            }

            ConnectionState.CONNECTING -> {
                when (event) {
                    is ConnectionEvent.ConnectionEstablished -> {
                        currentSession = event.session
                        currentPeer = event.session.peerDevice
                        reconnectAttempts = 0
                        state = ConnectionState.CONNECTED
                    }
                    is ConnectionEvent.ConnectionLost -> {
                        if (event.temporary && reconnectAttempts < maxReconnectAttempts) {
                            reconnectAttempts++
                            state = ConnectionState.RECONNECTING
                        } else {
                            resetToDisconnected()
                        }
                    }
                    is ConnectionEvent.Disconnect -> {
                        resetToDisconnected()
                    }
                    else -> Unit
                }
            }

            ConnectionState.CONNECTED -> {
                when (event) {
                    is ConnectionEvent.ConnectionLost -> {
                        currentSession = null
                        if (event.temporary && reconnectAttempts < maxReconnectAttempts) {
                            reconnectAttempts++
                            state = ConnectionState.RECONNECTING
                        } else {
                            resetToDisconnected()
                        }
                    }
                    is ConnectionEvent.Disconnect -> {
                        resetToDisconnected()
                    }
                    else -> Unit
                }
            }

            ConnectionState.RECONNECTING -> {
                when (event) {
                    is ConnectionEvent.ConnectionEstablished -> {
                        currentSession = event.session
                        currentPeer = event.session.peerDevice
                        reconnectAttempts = 0
                        state = ConnectionState.CONNECTED
                    }
                    is ConnectionEvent.ConnectionLost -> {
                        if (reconnectAttempts < maxReconnectAttempts) {
                            reconnectAttempts++
                            state = ConnectionState.RECONNECTING
                        } else {
                            state = ConnectionState.DISCOVERING
                        }
                    }
                    is ConnectionEvent.RetryBudgetExhausted -> {
                        state = ConnectionState.DISCOVERING
                    }
                    is ConnectionEvent.Disconnect -> {
                        resetToDisconnected()
                    }
                    else -> Unit
                }
            }
        }
        return state
    }

    private fun resetToDisconnected() {
        state = ConnectionState.DISCONNECTED
        currentPeer = null
        currentSession = null
        reconnectAttempts = 0
    }

    fun backoffDelaySeconds(attempt: Int): Double {
        val exponential = baseReconnectDelaySeconds * 2.0.pow(min(attempt, 6).toDouble())
        val jitter = Random.nextDouble(0.8, 1.2)
        return exponential * jitter
    }
}
