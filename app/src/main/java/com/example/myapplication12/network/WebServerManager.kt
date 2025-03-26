package com.example.myapplication12.network // Ensure this matches your file location

import android.util.Log
import com.google.gson.Gson
// BuildConfig import is removed as it's not used in this file snippet. Add back if needed elsewhere.
import com.example.myapplication12.service.ServiceState
// Ktor Imports
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.* // Or CIO
// Correct specific import for CallLogging
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.*
// Correct CORS import for Ktor 2.x/3.x
import io.ktor.server.plugins.cors.CORS
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.server.response.*
// Coroutine Imports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// kotlinx.html Imports
import kotlinx.html.*
// Other Imports
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.TimeUnit
// WebRTC Import (Ensure org.webrtc:google-webrtc dependency is synced)
import org.webrtc.IceCandidate
// Import for Kotlin Duration
import kotlin.time.Duration.Companion.seconds


// Data class for signaling messages (simple example)
sealed class SignalingMessage {
    data class Sdp(val type: String, val sdp: String) : SignalingMessage() // "offer" or "answer"
    data class IceCandidate(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String) : SignalingMessage()
    data class Error(val message: String) : SignalingMessage()
}

class WebServerManager(
    private val serviceScope: CoroutineScope,
    private val onSdpOffer: suspend (session: DefaultWebSocketServerSession, sdp: String) -> Unit,
    private val onIceCandidate: suspend (session: DefaultWebSocketServerSession, sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit
) : Closeable {

    private val TAG = "WebServerManager"
    private val PORT = 8080
    // This type mismatch error LIKELY resolves after fixing other unresolved references and cache invalidation.
    private var server: ApplicationEngine? = null
    private var serverJob: Job? = null
    private val gson = Gson()

    private val connections: MutableSet<DefaultWebSocketServerSession> = Collections.synchronizedSet(LinkedHashSet())
    private val _broadcastFlow = MutableSharedFlow<String>()

    private val broadcastJob = serviceScope.launch {
        _broadcastFlow.collect { message ->
            val sessionsToSend = synchronized(connections) { connections.toList() }
            val closedSessions = mutableListOf<DefaultWebSocketServerSession>()

            sessionsToSend.forEach { session ->
                try {
                    if (session.isActive) {
                        // Use withContext for safety if send is complex, though often not needed here
                        withContext(Dispatchers.IO) { session.send(Frame.Text(message)) }
                    } else {
                        closedSessions.add(session)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error broadcasting to session ${session.hashCode()}", e)
                    closedSessions.add(session)
                }
            }

            if (closedSessions.isNotEmpty()) {
                synchronized(connections) { connections.removeAll(closedSessions.toSet()) }
                Log.i(TAG, "Attempted removal of ${closedSessions.size} inactive/error sessions.")
                updateClientCount()
            }
        }
    }


    fun startServer(hostIp: String?) {
        if (serverJob?.isActive == true) {
            Log.w(TAG, "Server is already running.")
            return
        }
        if (hostIp == null) {
            Log.e(TAG, "Cannot start server without a valid IP address.")
            ServiceState.postError("Network unavailable to start server.")
            return
        }

        Log.i(TAG, "Starting Ktor server on $hostIp:$PORT...")

        serverJob = serviceScope.launch(Dispatchers.IO) {
            try {
                // Ensure all plugins (CallLogging, CORS, etc.) resolve correctly.
                // If they don't, embeddedServer might return unexpected types leading to mismatch error.
                server = embeddedServer(Netty, port = PORT, host = hostIp) {
                    // Configuration order matters sometimes, ensure serialization is early if routes use it
                    configureSerialization()
                    configureLoggingAndCORS() // Installs CallLogging and CORS
                    configureRouting()
                    configureSockets()
                }.start(wait = true)

                Log.i(TAG, "Ktor server stopped gracefully.")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start or run Ktor server", e)
                ServiceState.postError("Server failed: ${e.message}")
                stopServerInternal()
            }
        }

        serverJob?.invokeOnCompletion { throwable ->
            val cause = throwable?.message ?: "Normal stop"
            Log.i(TAG, "Server job completed. Cause: $cause")
            if (throwable != null && isActive) {
                ServiceState.postError("Server stopped unexpectedly: ${throwable.message}")
            }
            stopServerInternal()
            ServiceState.setServerAddress(null)
        }

        ServiceState.setServerAddress(hostIp, PORT)
    }

    fun stopServer() {
        Log.i(TAG, "Requesting Ktor server stop...")
        // Cancel the job first, letting invokeOnCompletion handle cleanup via stopServerInternal
        serverJob?.cancel()
        serverJob = null
        // Call internal stop immediately as well for faster resource release attempt, it handles null server safely
        stopServerInternal()
    }

    private fun stopServerInternal() {
        if (server == null) {
            // Log.d(TAG, "Server instance was already null during internal stop.")
            return // Avoid further processing if already stopped/null
        }
        Log.d(TAG, "Stopping embedded server instance...")

        // Close active connections
        val sessionsToClose = synchronized(connections) { connections.toList() }
        sessionsToClose.forEach { session ->
            serviceScope.launch {
                try {
                    session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
                } catch (e: Exception) { Log.w(TAG, "Error closing session ${session.hashCode()}: ${e.message}") }
            }
        }
        synchronized(connections) {
            if (connections.isNotEmpty()) {
                Log.d(TAG, "Clearing ${connections.size} tracked connections.")
                connections.clear()
            }
        }
        updateClientCount()

        // Stop the server engine
        server?.stop(100, 500, TimeUnit.MILLISECONDS)
        Log.i(TAG, "Ktor server stop method called.")
        server = null // Release the server instance
    }

    suspend fun sendSdpAnswer(sdp: String) {
        try {
            val message = gson.toJson(SignalingMessage.Sdp(type = "answer", sdp = sdp))
            Log.d(TAG, "Queuing SDP Answer for broadcast: ${message.length} chars")
            _broadcastFlow.emit(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing/sending SDP answer", e)
        }
    }

    // Ensure WebRtcIceCandidate (org.webrtc.IceCandidate) resolves after cache invalidation
    suspend fun sendIceCandidate(candidate:IceCandidate) {
        try {
            val message = gson.toJson(SignalingMessage.IceCandidate(
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex,
                candidate = candidate.sdp
            ))
            Log.d(TAG, "Queuing ICE Candidate for broadcast: ${candidate.sdpMid} / ${candidate.sdpMLineIndex}")
            _broadcastFlow.emit(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing/sending ICE candidate", e)
        }
    }

    // --- Ktor Module Configuration ---

    private fun Application.configureRouting() {
        routing {
            get("/") {
                // respondHtml should resolve if ktor-server-html-builder & kotlinx-html-jvm are synced
                call.respondHtml(HttpStatusCode.OK) {
                    head {
                        title("AR Streamer Client")
                        style {
                            unsafe {
                                raw("""
                                body { font-family: sans-serif; margin: 20px; background-color: #222; color: #eee;}
                                video { max-width: 100%; border: 1px solid #555; background-color: #000;}
                                #status { margin-top: 10px; font-style: italic; color: #aaa; }
                                #arData { margin-top: 10px; white-space: pre-wrap; font-family: monospace; font-size: 0.8em; background-color: #333; padding: 5px; border-radius: 3px; color: #ddd;}
                                h1, h3 { color: #0a84ff; }
                            """.trimIndent())
                            }
                        }
                    }
                    body {
                        h1 { +"AR Streamer Web Client" }
                        p { +"Attempting to connect to AR Streamer..." }
                        video {
                            id = "remoteVideo"
                            autoPlay = true
                            attributes["playsinline"] = "true"
                            // muted = true // Consider adding for autoplay
                        }
                        div { id = "status" }
                        div {
                            h3 { +"AR Data:" }
                            pre { id = "arData" }
                        }
                        script {
                            unsafe { raw(getClientSideJs()) }
                        }
                    }
                }
            }
            get("/capture") {
                // respondText should resolve if core ktor/response dependencies are synced
                call.respondText("Image capture endpoint (Not Implemented Yet)", ContentType.Text.Plain)
            }
        }
    }

    private fun Application.configureSockets() {
        // Ensure ktor-server-websockets dependency is synced
        install(WebSockets) {
            // FIX: Use kotlin.time.Duration
            pingPeriod = 15.seconds
            timeout = 15.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        routing {
            webSocket("/ws") {
                Log.i(TAG, "WebSocket client connected: ${this.hashCode()}")
                connections.add(this)
                updateClientCount()

                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            try {
                                // Ensure SignalingMessage types resolve
                                when {
                                    text.contains("\"type\":\"offer\"") -> {
                                        val sdpOffer = gson.fromJson(text, SignalingMessage.Sdp::class.java)
                                        onSdpOffer(this, sdpOffer.sdp)
                                    }
                                    text.contains("\"candidate\"") -> {
                                        val iceCandidate = gson.fromJson(text, SignalingMessage.IceCandidate::class.java)
                                        onIceCandidate(this, iceCandidate.sdpMid, iceCandidate.sdpMLineIndex, iceCandidate.candidate)
                                    }
                                    else -> {
                                        Log.w(TAG, "Unknown WS message format received.")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing WS message: $text", e)
                                try {
                                    val errorMsg = gson.toJson(SignalingMessage.Error("Failed to parse message: ${e.message}"))
                                    send(Frame.Text(errorMsg))
                                } catch (sendEx: Exception) {
                                    Log.e(TAG, "Failed to send error message back to client", sendEx)
                                }
                            }
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    Log.i(TAG, "WebSocket client disconnected gracefully: ${this.hashCode()}")
                } catch (e: Throwable) {
                    Log.e(TAG, "WebSocket error for client ${this.hashCode()}:", e)
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Server error: ${e.message?.take(100)}"))
                } finally {
                    Log.i(TAG, "WebSocket client session ending: ${this.hashCode()}")
                    connections.remove(this)
                    updateClientCount()
                }
            }
        }
    }

    private fun Application.configureSerialization() {
        // Ensure ktor-server-content-negotiation and ktor-serialization-gson dependencies are synced
        install(ContentNegotiation) {
            gson { /* Optional config */ }
        }
    }

    private fun Application.configureLoggingAndCORS() {
        // Ensure ktor-server-call-logging dependency is synced
        // install(CallLogging) should resolve after cache invalidation
        install(CallLogging)

        // Ensure ktor-server-cors dependency is synced
        // install(CORS) should resolve. Ignore the misleading IDE message about .routing.
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Get)
            allowHeader(HttpHeaders.ContentType)
            anyHost() // WARNING: Development only
        }
    }

    private fun updateClientCount() {
        val count = synchronized(connections) { connections.size }
        serviceScope.launch(Dispatchers.Main.immediate) {
            if(ServiceState.connectedClients.value != count) {
                while(ServiceState.connectedClients.value < count) ServiceState.incrementConnectedClients()
                while(ServiceState.connectedClients.value > count) ServiceState.decrementConnectedClients()
                Log.i(TAG, "Updated connected WebSocket clients: $count")
            }
        }
    }

    // --- Simple WebRTC Client JavaScript ---
    private fun getClientSideJs(): String {
        // JS code remains the same - no changes needed here based on errors
        return """
            const videoElement = document.getElementById('remoteVideo');
            const statusElement = document.getElementById('status');
            const arDataElement = document.getElementById('arData');
            const wsUrl = `ws://${'$'}{window.location.host}/ws`; // Use current host for WS
            let peerConnection;
            let webSocket;
            let dataChannel;

            const config = {
                iceServers: [
                     { urls: 'stun:stun.l.google.com:19302' } // Public STUN server
                ]
            };

            function updateStatus(message) {
                console.log("Status:", message);
                statusElement.textContent = message;
            }

             function setupWebSocket() {
                 updateStatus("Connecting WebSocket to " + wsUrl + "...");
                 webSocket = new WebSocket(wsUrl);

                 webSocket.onopen = () => {
                     updateStatus("WebSocket Connected. Creating PeerConnection...");
                     createPeerConnection();
                 };

                 webSocket.onmessage = async (event) => {
                     try {
                         const message = JSON.parse(event.data);
                         console.log("WS Received:", message);

                         if (message.type === 'answer') {
                             updateStatus("Received SDP Answer.");
                             if (peerConnection.signalingState !== 'stable') {
                                 await peerConnection.setRemoteDescription(new RTCSessionDescription(message));
                                 updateStatus("Remote description set (Answer). Ready for ICE candidates.");
                             } else {
                                 console.warn("Received answer in stable state, ignoring.");
                             }
                         } else if (message.candidate) {
                             // updateStatus("Received ICE Candidate."); // Verbose
                             if (peerConnection.remoteDescription) {
                                 await peerConnection.addIceCandidate(new RTCIceCandidate(message));
                             } else {
                                console.warn("Received ICE candidate before remote description was set, queuing might be needed.");
                             }
                         } else if (message.error) {
                             console.error("Received error from server:", message.error);
                             updateStatus("Server Error: " + message.error);
                         } else {
                             console.warn("Unknown message type received:", message);
                         }
                    } catch (e) {
                         console.error('Error processing WebSocket message:', e);
                         updateStatus("Error processing message: " + e.message);
                    }
                 };

                 webSocket.onerror = (error) => {
                     console.error('WebSocket Error:', error);
                     updateStatus('WebSocket Error. See console.');
                 };

                 webSocket.onclose = (event) => {
                     updateStatus(`WebSocket Closed (Code: ${'$'}{event.code}, Reason: ${'$'}{event.reason || 'N/A'}). Streaming stopped.`);
                     if (peerConnection) {
                         peerConnection.close();
                         peerConnection = null;
                     }
                     videoElement.srcObject = null;
                     arDataElement.textContent = "";
                 };
             }

            function createPeerConnection() {
                try {
                    peerConnection = new RTCPeerConnection(config);
                    updateStatus("PeerConnection created.");

                    peerConnection.onicecandidate = event => {
                        if (event.candidate && webSocket && webSocket.readyState === WebSocket.OPEN) {
                            const message = {
                                candidate: event.candidate.candidate,
                                sdpMLineIndex: event.candidate.sdpMLineIndex,
                                sdpMid: event.candidate.sdpMid
                            };
                            webSocket.send(JSON.stringify(message));
                        }
                    };

                    peerConnection.ontrack = event => {
                        updateStatus("Received remote track: " + event.track.kind);
                        console.log("Track received:", event.track, event.streams);
                        if (event.streams && event.streams[0]) {
                            if (videoElement.srcObject !== event.streams[0]) {
                                videoElement.srcObject = event.streams[0];
                                videoElement.play().catch(e => console.error("Video play failed:", e));
                            }
                        }
                    };

                     peerConnection.ondatachannel = event => {
                        updateStatus("Received Data Channel: " + event.channel.label);
                        dataChannel = event.channel;
                        console.log("Data Channel received:", dataChannel.label, dataChannel.readyState);
                        setupDataChannelEvents();
                    };

                    peerConnection.oniceconnectionstatechange = () => {
                        updateStatus("ICE Connection State: " + peerConnection.iceConnectionState);
                        console.log("ICE Connection State:", peerConnection.iceConnectionState);
                        switch(peerConnection.iceConnectionState) {
                            case 'connected':
                            case 'completed':
                                updateStatus("Streaming connected!");
                                break;
                            case 'disconnected':
                                updateStatus("Streaming temporarily disconnected...");
                                break;
                            case 'failed':
                                updateStatus("Streaming connection failed. Check network/STUN/TURN.");
                                break;
                            case 'closed':
                                updateStatus("Streaming connection closed.");
                                arDataElement.textContent = "";
                                break;
                        }
                    };

                    createOffer();

                } catch (e) {
                    console.error("Failed to create PeerConnection:", e);
                    updateStatus("Error creating PeerConnection: " + e.message);
                }
            }

             function setupDataChannelEvents() {
                 if (!dataChannel) return;
                 dataChannel.onmessage = (event) => {
                    try {
                        const arData = JSON.parse(event.data);
                        arDataElement.textContent = JSON.stringify(arData, null, 2);
                    } catch (e) {
                        console.error("Failed to parse AR data:", e);
                        arDataElement.textContent = "Error parsing AR data: " + event.data;
                    }
                };
                dataChannel.onopen = () => {
                     updateStatus("Data Channel Open.");
                     console.log("Data Channel is open");
                };
                dataChannel.onclose = () => {
                     updateStatus("Data Channel Closed.");
                     console.log("Data Channel is closed");
                     arDataElement.textContent = "";
                };
                 dataChannel.onerror = (error) => {
                    console.error("Data Channel Error:", error);
                    updateStatus("Data Channel Error: Check console.");
                };
             }

            async function createOffer() {
                 if (!peerConnection || peerConnection.signalingState !== 'stable') {
                     console.warn(`Cannot create offer in signaling state: ${'$'}{peerConnection?.signalingState}. Aborting.`);
                     updateStatus(`Cannot create offer now (state: ${'$'}{peerConnection?.signalingState})`);
                     return;
                 }
                 try {
                     const offerOptions = {
                        offerToReceiveVideo: true,
                        offerToReceiveAudio: true
                    };
                     updateStatus("Creating SDP Offer...");
                     const offer = await peerConnection.createOffer(offerOptions);
                     await peerConnection.setLocalDescription(offer);
                     updateStatus("SDP Offer created. Sending to server...");

                     const message = { type: 'offer', sdp: offer.sdp };
                     if (webSocket && webSocket.readyState === WebSocket.OPEN) {
                        webSocket.send(JSON.stringify(message));
                        console.log("Sent Offer");
                     } else {
                         updateStatus("Cannot send offer, WebSocket not open.");
                         console.error("WebSocket is not open. Cannot send SDP offer.");
                     }
                 } catch (e) {
                     console.error("Failed to create or send offer:", e);
                     updateStatus("Error creating/sending offer: " + e.message);
                 }
            }

            setupWebSocket();

        """.trimIndent()
    }

    override fun close() {
        Log.i(TAG, "Closing WebServerManager.")
        broadcastJob.cancel()
        stopServer()
    }

} // End of WebServerManager class