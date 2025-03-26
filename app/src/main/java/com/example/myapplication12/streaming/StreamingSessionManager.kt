package com.example.myapplication12.streaming

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.myapplication12.ar.ARManager
import com.example.myapplication12.model.ARFrameData
import com.example.myapplication12.model.StreamMode
import com.example.myapplication12.network.WebServerManager
import com.example.myapplication12.service.ServiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.webrtc.*
import org.webrtc.voiceengine.WebRtcAudioRecord
import org.webrtc.voiceengine.WebRtcAudioUtils
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class StreamingSessionManager(
    private val context: Context,
    private val webServerManager: WebServerManager,
    private val arManager: ARManager?, // Needed for AR data
    private val serviceScope: CoroutineScope,
    initialStreamMode: StreamMode
) : Closeable {

    private val TAG = "StreamingSession"
    private val EGL_BASE: EglBase = EglBase.create() // Shared EGL context for WebRTC video
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var yuvConverter: YuvConverter? = null // Helper to convert YUV ImageProxy to I420

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Job? = null
    private val audioExecutor = Executors.newSingleThreadExecutor() // Dedicated executor for audio

    private var dataChannel: DataChannel? = null
    private var arDataJob: Job? = null // Job for collecting AR data

    private var currentStreamMode: StreamMode = initialStreamMode

    init {
        Log.d(TAG, "Initializing PeerConnectionFactory...")
        // Initialize PeerConnectionFactory options
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true) // Enable tracing for debugging
            //.setFieldTrials("WebRTC-H264HighProfile/Enabled/") // Example field trial
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        // Create PeerConnectionFactory
        val options = PeerConnectionFactory.Options()
        // options.networkIgnoreMask = 0 // Optional: Configure network ignore mask

        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
            EGL_BASE.eglBaseContext, true /* enableIntelVp8Encoder */, true /* enableH264HighProfile */
        )
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(EGL_BASE.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .setAudioDeviceModule(createJavaAudioDeviceModule()) // Use Java audio device module
            .createPeerConnectionFactory()

        Log.i(TAG, "PeerConnectionFactory initialized.")
    }

    // Call this when the stream mode changes
    fun updateStreamMode(newMode: StreamMode) {
        Log.i(TAG, "Updating stream mode from $currentStreamMode to $newMode")
        if (newMode == currentStreamMode) return

        // Stop existing tracks/sources based on the OLD mode
        stopTracks(currentStreamMode)

        currentStreamMode = newMode

        // Start new tracks/sources based on the NEW mode
        if (peerConnection != null) {
            setupTracksAndSources(newMode)
            // If already connected, renegotiation might be needed
            // This is complex, might involve creating a new offer
            Log.w(TAG, "Stream mode changed while connected. Renegotiation might be required (not implemented).")
        }
        // If not connected, setupTracksAndSources will be called when createPeerConnection happens
    }


    // Called by WebServerManager when an SDP Offer is received
    suspend fun onSdpOfferReceived(sdp: String) {
        Log.i(TAG, "SDP Offer received.")
        if (peerConnection == null) {
            Log.d(TAG, "PeerConnection is null, creating...")
            createPeerConnection()
        }

        peerConnection?.let { pc ->
            try {
                withContext(Dispatchers.IO) { // WebRTC operations should run off main thread
                    val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
                    pc.setRemoteDescription(SdpObserverAdapter("SetRemoteOffer"), sessionDescription)
                    Log.d(TAG, "Set remote description (Offer) successful.")

                    // Create SDP Answer
                    val constraints = MediaConstraints() // Add constraints if needed
                    // constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    // constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    Log.d(TAG, "Creating SDP Answer...")
                    pc.createAnswer(SdpObserverAdapter("CreateAnswer") { answerSdp ->
                        Log.d(TAG, "SDP Answer created successfully.")
                        pc.setLocalDescription(SdpObserverAdapter("SetLocalAnswer") {
                            Log.d(TAG, "Set local description (Answer) successful.")
                            // Send the answer back via WebSocketManager
                            serviceScope.launch { webServerManager.sendSdpAnswer(answerSdp.description) }
                        }, answerSdp)
                    }, constraints)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SDP Offer", e)
                ServiceState.postError("WebRTC Offer processing failed: ${e.message}")
                closePeerConnection() // Clean up on error
            }
        } ?: Log.e(TAG, "PeerConnection is null when processing offer.")
    }

    // Called by WebServerManager when an ICE Candidate is received
    suspend fun onIceCandidateReceived(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        Log.d(TAG, "ICE Candidate received: $sdpMid / $sdpMLineIndex")
        withContext(Dispatchers.IO) {
            peerConnection?.addIceCandidate(IceCandidate(sdpMid ?: "", sdpMLineIndex, candidate))
            Log.d(TAG, "Added remote ICE candidate.")
        }
    }

    // Call this when a new ImageProxy is available from CameraManager
    fun onFrameAvailable(imageProxy: ImageProxy) {
        if (currentStreamMode != StreamMode.VIDEO_ONLY && currentStreamMode != StreamMode.VIDEO_AUDIO_AR) {
            imageProxy.close() // Close if not needed for current mode
            return
        }
        videoSource?.capturerObserver?.let { capturerObserver ->
            val rotation = imageProxy.imageInfo.rotationDegrees
            val timestampNs = imageProxy.imageInfo.timestamp * 1_000_000 // Convert ms to ns for WebRTC

            imageProxy.image?.let { image ->
                // Convert YUV_420_888 to I420 needed by WebRTC
                // Create YuvConverter if not already done (needs EGL context)
                if (yuvConverter == null) {
                    yuvConverter = YuvConverter()
                }

                val i420Buffer = yuvConverter!!.convert(image) // Returns an I420Buffer
                val videoFrame = VideoFrame(i420Buffer, rotation, timestampNs)

                // Pass the frame to the WebRTC video source
                capturerObserver.onFrameCaptured(videoFrame)
                videoFrame.release() // Release the frame, YuvConverter handles buffer internally

            } ?: Log.w(TAG, "ImageProxy.image is null")

        } ?: Log.w(TAG, "Video capturer observer is null, cannot process frame.")

        // IMPORTANT: Close the ImageProxy to allow CameraX to provide the next frame
        imageProxy.close()
    }

    // --- PeerConnection Setup ---

    private fun createPeerConnection() {
        Log.i(TAG, "Creating PeerConnection...")
        if (peerConnection != null) {
            Log.w(TAG, "Closing existing PeerConnection before creating a new one.")
            closePeerConnection()
        }

        val rtcConfig = PeerConnection.RTCConfiguration(getIceServers())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN // Recommended
        // rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY // Optional

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            PeerConnectionObserverAdapter("PC Observer")
        )

        if (peerConnection == null) {
            Log.e(TAG, "Failed to create PeerConnection.")
            ServiceState.postError("WebRTC PeerConnection creation failed.")
            return
        }
        Log.i(TAG, "PeerConnection created.")
        ServiceState.setStreaming(true) // Indicate streaming is attempting to start

        // Setup tracks based on the *current* selected mode
        setupTracksAndSources(currentStreamMode)
    }

    private fun getIceServers(): List<PeerConnection.IceServer> {
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        // Add Google's public STUN server
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        // Add more STUN servers
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
        // iceServers.add(PeerConnection.IceServer.builder("stun:stun.xten.com:3478").createIceServer())

        // TODO: Add TURN server configuration if needed for NAT traversal
        // val turnServer = PeerConnection.IceServer.builder("turn:your.turn.server.com:3478")
        //     .setUsername("your_username")
        //     .setPassword("your_password")
        //     .createIceServer()
        // iceServers.add(turnServer)

        Log.d(TAG, "Using ICE Servers: ${iceServers.joinToString { it.urls.firstOrNull() ?: "N/A" }}")
        return iceServers
    }

    private fun setupTracksAndSources(mode: StreamMode) {
        peerConnection?.let { pc ->
            Log.i(TAG, "Setting up tracks for mode: $mode")

            // --- Video Track ---
            if (mode == StreamMode.VIDEO_ONLY || mode == StreamMode.VIDEO_AUDIO_AR) {
                if (videoTrack == null) {
                    Log.d(TAG, "Creating video track.")
                    videoSource = peerConnectionFactory.createVideoSource(false /* isScreencast */)
                    // Create a capturer observer; CameraManager will push frames via onFrameAvailable
                    // The VideoSource itself acts as the observer sink here.

                    videoTrack = peerConnectionFactory.createVideoTrack("videoTrack_0", videoSource)
                    pc.addTrack(videoTrack, listOf("stream_0")) // Add track to the peer connection
                    Log.i(TAG, "Video track created and added.")
                }
            }

            // --- Audio Track ---
            if (mode == StreamMode.AUDIO_ONLY || mode == StreamMode.VIDEO_AUDIO_AR) {
                if (audioTrack == null) {
                    Log.d(TAG, "Creating audio track.")
                    val audioConstraints = MediaConstraints()
                    // Add audio constraints if needed (e.g., disable echo cancellation)
                    // audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))

                    audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
                    audioTrack = peerConnectionFactory.createAudioTrack("audioTrack_0", audioSource)
                    pc.addTrack(audioTrack, listOf("stream_0")) // Add track to the same stream
                    Log.i(TAG, "Audio track created and added.")
                    startAudioCapture() // Start capturing mic data
                }
            }

            // --- Data Channel ---
            if (mode == StreamMode.VIDEO_AUDIO_AR && arManager != null) {
                if (dataChannel == null) {
                    Log.d(TAG, "Creating data channel.")
                    val dcInit = DataChannel.Init()
                    // Configure DataChannel options (ordered, reliable, etc.)
                    // dcInit.ordered = true
                    // dcInit.maxRetransmits = -1 // Reliable
                    // dcInit.protocol = "ar-data"
                    dataChannel = pc.createDataChannel("arDataChannel", dcInit)
                    registerDataChannelObserver()
                    Log.i(TAG, "Data channel created.")
                    startArDataStreaming() // Start collecting and sending AR data
                }
            }
        } ?: Log.e(TAG, "Cannot setup tracks, PeerConnection is null.")
    }

    // Stop and remove tracks based on the mode they belong to
    private fun stopTracks(mode: StreamMode) {
        peerConnection?.let { pc ->
            Log.i(TAG, "Stopping tracks for mode: $mode")

            // Stop Video
            if (mode == StreamMode.VIDEO_ONLY || mode == StreamMode.VIDEO_AUDIO_AR) {
                videoTrack?.let { track ->
                    Log.d(TAG, "Removing video track.")
                    try {
                        val sender = pc.senders.find { it.track()?.id() == track.id() }
                        sender?.let { pc.removeTrack(it) }
                        Log.i(TAG, "Video track removed from PeerConnection.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing video track", e)
                    }
                    track.dispose()
                    Log.d(TAG, "Video track disposed.")
                }
                videoSource?.dispose()
                Log.d(TAG, "Video source disposed.")
                videoTrack = null
                videoSource = null
                yuvConverter?.release() // Release YUV converter resources
                yuvConverter = null
                Log.d(TAG, "YUV Converter released.")
            }

            // Stop Audio
            if (mode == StreamMode.AUDIO_ONLY || mode == StreamMode.VIDEO_AUDIO_AR) {
                stopAudioCapture() // Stop mic recording
                audioTrack?.let { track ->
                    Log.d(TAG, "Removing audio track.")
                    try {
                        val sender = pc.senders.find { it.track()?.id() == track.id() }
                        sender?.let { pc.removeTrack(it) }
                        Log.i(TAG, "Audio track removed from PeerConnection.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing audio track", e)
                    }
                    track.dispose()
                    Log.d(TAG, "Audio track disposed.")
                }
                audioSource?.dispose()
                Log.d(TAG, "Audio source disposed.")
                audioTrack = null
                audioSource = null
            }

            // Stop AR Data
            if (mode == StreamMode.VIDEO_AUDIO_AR) {
                stopArDataStreaming() // Stop collecting AR data
                dataChannel?.let { dc ->
                    Log.d(TAG, "Closing and unregistering data channel.")
                    dc.unregisterObserver()
                    dc.close()
                    // Dispose might not be needed or available depending on library version
                    // dc.dispose()
                    Log.i(TAG, "Data channel closed.")
                }
                dataChannel = null
            }

        } ?: Log.d(TAG, "PeerConnection is null, cannot stop tracks.")
    }

    private fun registerDataChannelObserver() {
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                Log.d(TAG, "DataChannel buffered amount changed: $previousAmount -> ${dataChannel?.bufferedAmount()}")
            }

            override fun onStateChange() {
                val state = dataChannel?.state()
                Log.i(TAG, "DataChannel state changed: $state")
                when (state) {
                    DataChannel.State.OPEN -> {
                        Log.i(TAG, "DataChannel OPEN. Ready to send AR data.")
                        // Can optionally send a confirmation message
                        // sendArData("{\"status\":\"Data channel connected\"}")
                    }
                    DataChannel.State.CLOSING, DataChannel.State.CLOSED -> {
                        Log.i(TAG, "DataChannel closed or closing.")
                        stopArDataStreaming() // Stop sending data if channel closes
                    }
                    else -> {} // CONNECTING
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                // This app primarily sends data, but handle incoming messages if needed
                Log.d(TAG, "DataChannel message received (unexpected). Binary: ${buffer.binary}")
                // Example: Read text data
                if (!buffer.binary) {
                    val byteBuffer = buffer.data
                    val bytes = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(bytes)
                    val message = String(bytes, Charsets.UTF_8)
                    Log.d(TAG, "Received DC Text Message: $message")
                }
                // Remember to release the buffer? Check documentation.
            }
        })
    }

    private fun startArDataStreaming() {
        if (arDataJob?.isActive == true) {
            Log.w(TAG, "AR data streaming job already active.")
            return
        }
        if (arManager == null) {
            Log.e(TAG, "ARManager is null, cannot stream AR data.")
            return
        }

        Log.i(TAG, "Starting AR data streaming...")
        arDataJob = serviceScope.launch(Dispatchers.Default) {
            arManager.arFrameDataFlow.collectLatest { arData ->
                // Only send if data channel is open
                if (dataChannel?.state() == DataChannel.State.OPEN) {
                    val jsonString = DataSerializer.serializeARFrameData(arData)
                    if (jsonString != null) {
                        sendArData(jsonString)
                    }
                } else {
                    // Log.v(TAG, "Data channel not open, skipping AR data send.")
                }
            }
        }
        arDataJob?.invokeOnCompletion {
            Log.i(TAG, "AR data streaming job completed. Cause: ${it?.message ?: "Normal stop"}")
        }
    }

    private fun stopArDataStreaming() {
        if (arDataJob?.isActive == true) {
            Log.i(TAG, "Stopping AR data streaming job...")
            arDataJob?.cancel()
        }
        arDataJob = null
    }

    private fun sendArData(jsonData: String) {
        dataChannel?.let { dc ->
            if (dc.state() == DataChannel.State.OPEN) {
                val buffer = ByteBuffer.wrap(jsonData.toByteArray(Charsets.UTF_8))
                val dataBuffer = DataChannel.Buffer(buffer, false) // false for text data
                val sent = dc.send(dataBuffer)
                if (!sent) {
                    Log.w(TAG, "DataChannel send buffer is full. AR data might be dropped.")
                    // Implement backpressure if needed (check bufferedAmount)
                } else {
                    // Log.v(TAG, "Sent AR data (${jsonData.length} bytes)")
                }
            }
        }
    }

    private fun closePeerConnection() {
        Log.i(TAG, "Closing PeerConnection...")
        ServiceState.setStreaming(false) // Update state

        // Stop tracks before closing PC
        stopTracks(currentStreamMode) // Stop tracks associated with the current mode

        peerConnection?.close() // Close the connection
        peerConnection = null
        Log.i(TAG, "PeerConnection closed.")
    }


    // --- Audio Capture Setup (Using Android AudioRecord) ---

    private fun createJavaAudioDeviceModule(): AudioDeviceModule {
        // Configure audio device module settings
        WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true)
        WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true)
        // WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true) // Can sometimes cause issues

        return JavaAudioDeviceModule.builder(context)
            // Set audio source (MIC) and format
            .setUseHardwareAcousticEchoCanceler(true) // Use hardware AEC if available
            .setUseHardwareNoiseSuppressor(true) // Use hardware NS if available
            // .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION) // Recommended for comms
            .setAudioFormat(AudioFormat.ENCODING_PCM_16BIT)
            // Add other configurations as needed
            .createAudioDeviceModule()
    }


    // --- Start/Stop Raw Audio Capture (Alternative if JavaAudioDeviceModule is insufficient) ---
    // This sends raw PCM data directly. JavaAudioDeviceModule is generally preferred.
    /*
   private val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC // Or VOICE_COMMUNICATION
   private val SAMPLE_RATE = 48000 // Match WebRTC internal processing if possible
   private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
   private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
   private val BUFFER_SIZE_FACTOR = 2
   private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR

   @SuppressLint("MissingPermission") // Permissions should be checked before starting
   private fun startAudioCapture() {
       if (audioRecord != null || audioThread?.isActive == true) {
           Log.w(TAG, "Audio capture already running.")
           return
       }

       Log.i(TAG, "Starting audio capture. Buffer size: $bufferSize bytes")
       try {
           audioRecord = AudioRecord(
               AUDIO_SOURCE,
               SAMPLE_RATE,
               CHANNEL_CONFIG,
               AUDIO_FORMAT,
               bufferSize
           )

           if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
               Log.e(TAG, "AudioRecord initialization failed.")
               audioRecord?.release()
               audioRecord = null
               ServiceState.postError("Microphone initialization failed.")
               return
           }

           audioRecord?.startRecording()

           audioThread = serviceScope.launch(Dispatchers.IO) { // Use IO dispatcher for blocking read
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) // Set higher priority

               val audioBuffer = ByteBuffer.allocateDirect(bufferSize)
               Log.d(TAG, "Audio capture thread started.")

               while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                   val bytesRead = audioRecord?.read(audioBuffer, bufferSize)
                   if (bytesRead != null && bytesRead > 0) {
                       // Pass the raw PCM data to WebRTC's AudioTrack/Source
                       // This requires interacting with the native layer or specific methods
                       // provided by the WebRTC library to push external audio frames.
                       // The JavaAudioDeviceModule handles this automatically.
                       // If using external capture, you'd call something like:
                       // audioSource?.OnData(audioBuffer, bytesRead, SAMPLE_RATE, 1 /* channels */, 16 /* bits */)
                       // This part is complex and depends heavily on the WebRTC library version.
                        Log.v(TAG, "Read $bytesRead audio bytes.") // Verbose Log

                        // Reset buffer position for next read if needed by WebRTC API
                       audioBuffer.clear()

                   } else if (bytesRead != null && bytesRead < 0) {
                       Log.e(TAG, "AudioRecord read error: $bytesRead")
                       break // Exit loop on error
                   }
                    // Add small delay if needed, but read should block
                    // delay(1)
               }
                Log.i(TAG, "Audio capture loop finished.")
           }
           audioThread?.invokeOnCompletion {
               Log.i(TAG, "Audio capture thread stopped. Cause: ${it?.message ?: "Normal stop"}")
                // Ensure cleanup happens when thread stops
                stopAudioCaptureInternal()
           }

       } catch (e: Exception) {
           Log.e(TAG, "Failed to start audio capture", e)
           stopAudioCaptureInternal()
           ServiceState.postError("Audio capture failed: ${e.message}")
       }
   }

    private fun stopAudioCapture() {
        Log.i(TAG, "Requesting audio capture stop...")
        audioThread?.cancel() // Cancel the coroutine
        // Internal cleanup will happen in invokeOnCompletion or here
        stopAudioCaptureInternal()
        audioThread = null
   }

    private fun stopAudioCaptureInternal() {
       if (audioRecord != null) {
           Log.d(TAG, "Stopping and releasing AudioRecord.")
           if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
               try {
                   audioRecord?.stop()
               } catch (e: IllegalStateException) {
                   Log.e(TAG, "AudioRecord stop failed", e)
               }
           }
           audioRecord?.release()
           audioRecord = null
           Log.i(TAG, "AudioRecord released.")
       }
   }
   */
    // Using JavaAudioDeviceModule, these manual methods are not typically needed.
    private fun startAudioCapture() {
        Log.i(TAG, "Audio capture managed by JavaAudioDeviceModule.")
        // JavaAudioDeviceModule handles mic access when audio track is added/enabled
    }

    private fun stopAudioCapture() {
        Log.i(TAG, "Audio capture managed by JavaAudioDeviceModule.")
        // JavaAudioDeviceModule handles mic release when audio track is removed/disabled
    }


    // --- Closeable ---

    override fun close() {
        Log.d(TAG, "Closing StreamingSessionManager.")
        // audioExecutor.shutdown() // Shutdown audio executor if used
        closePeerConnection() // Close WebRTC connection
        videoSource?.dispose()
        audioSource?.dispose()
        yuvConverter?.release()
        peerConnectionFactory.dispose() // Dispose the factory
        EGL_BASE.release() // Release EGL context
        Log.i(TAG, "StreamingSessionManager resources released.")
    }

    // --- Adapter Classes for Observers ---

    private open inner class SdpObserverAdapter(
        private val tag: String,
        private val onSuccessCallback: ((SessionDescription) -> Unit)? = null
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            Log.d(TAG, "$tag: onCreateSuccess")
            sdp?.let { onSuccessCallback?.invoke(it) } ?: Log.e(TAG, "$tag: onCreateSuccess called with null SDP")
        }

        override fun onSetSuccess() {
            Log.d(TAG, "$tag: onSetSuccess")
            // If it's SetLocalAnswer, the SDP can now be sent
            // This logic is handled in onSdpOfferReceived for clarity now
            // if (tag == "SetLocalAnswer") { ... send answer ... }
            onSuccessCallback?.invoke(SessionDescription(SessionDescription.Type.ANSWER,"")) // Invoke callback on set success too if needed for flow control
        }

        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "$tag: onCreateFailure: $error")
            ServiceState.postError("WebRTC SDP Create Failed: $error")
            closePeerConnection() // Clean up on failure
        }

        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$tag: onSetFailure: $error")
            ServiceState.postError("WebRTC SDP Set Failed: $error")
            closePeerConnection() // Clean up on failure
        }
    }

    private inner class PeerConnectionObserverAdapter(private val tag: String) : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.i(TAG, "$tag: SignalingState changed: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(TAG, "$tag: IceConnectionState changed: $state")
            // Update UI based on state (Connected, Failed, Disconnected)
            serviceScope.launch(Dispatchers.Main.immediate) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        // Handle connected state - perhaps update UI indicator
                        Log.i(TAG, "WebRTC client connected.")
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        ServiceState.postError("WebRTC connection failed.")
                        // Consider ICE restart or closing connection
                        // peerConnection?.restartIce()
                        closePeerConnection()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        // Connection lost, might reconnect
                        Log.w(TAG, "WebRTC client disconnected.")
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        Log.i(TAG, "WebRTC connection closed.")
                        // Ensure cleanup if closed unexpectedly
                        // closePeerConnection() // Might be redundant if called elsewhere
                    }
                    else -> {} // NEW, CHECKING
                }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "$tag: IceConnectionReceiving changed: $receiving")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "$tag: IceGatheringState changed: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            Log.d(TAG, "$tag: Local ICE Candidate gathered: ${candidate?.sdpMid} / ${candidate?.sdpMLineIndex}")
            candidate?.let {
                // Send the candidate to the remote peer via WebSocketManager
                serviceScope.launch { webServerManager.sendIceCandidate(it) }
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            Log.d(TAG, "$tag: ICE Candidates removed: ${candidates?.size}")
            // May need to signal removal to remote peer if using trickle ICE
        }

        override fun onAddStream(stream: MediaStream?) {
            Log.i(TAG, "$tag: onAddStream (Deprecated) - ID: ${stream?.id}")
            // Deprecated in Unified Plan, use onTrack instead
        }

        override fun onRemoveStream(stream: MediaStream?) {
            Log.w(TAG, "$tag: onRemoveStream (Deprecated) - ID: ${stream?.id}")
        }

        override fun onDataChannel(dataChannel: DataChannel?) {
            Log.i(TAG, "$tag: onDataChannel received - Label: ${dataChannel?.label()}")
            // This is called if the *remote* peer creates a data channel
            // In our case, the Android app creates it, so this shouldn't be called typically
            dataChannel?.let {
                // If we need to handle remotely initiated channels:
                // this@StreamingSessionManager.dataChannel = it // Assign if needed
                // registerDataChannelObserver()
            }
        }

        override fun onRenegotiationNeeded() {
            Log.w(TAG, "$tag: onRenegotiationNeeded - (Not handled automatically)")
            // Called when tracks are added/removed after connection established
            // Requires creating a new offer and sending it.
            // ServiceState.postError("WebRTC renegotiation needed (manual trigger required).")
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            val track = receiver?.track()
            Log.i(TAG, "$tag: onAddTrack - Kind: ${track?.kind()}, ID: ${track?.id()}")
            // This is called when the remote peer adds a track (e.g., if it were sending video/audio back)
            // In this streamer app, we don't expect tracks from the client, but log it.
        }
    }
}