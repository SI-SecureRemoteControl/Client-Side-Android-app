package ba.unsa.etf.si.secureremotecontrol.presentation.main

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import ba.unsa.etf.si.secureremotecontrol.data.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import ba.unsa.etf.si.secureremotecontrol.data.webrtc.WebRTCManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import ba.unsa.etf.si.secureremotecontrol.service.RemoteControlAccessibilityService
import ba.unsa.etf.si.secureremotecontrol.service.ScreenSharingService
import okhttp3.OkHttpClient
import org.json.JSONException
import ba.unsa.etf.si.secureremotecontrol.data.fileShare.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import android.Manifest
import android.net.Uri
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.documentfile.provider.DocumentFile

@HiltViewModel
class MainViewModel @Inject constructor(
    private val webSocketService: WebSocketService,
    private val apiService: ApiService,
    @ApplicationContext private val context: Context,
    private val tokenDataStore: TokenDataStore,
    private val webRTCManager: WebRTCManager,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val TAG = "MainViewModel"

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState

    private var messageObservationJob: Job? = null
    private var timeoutJob: Job? = null

    // File Sharing State
    private val _fileShareState = MutableStateFlow<FileShareState>(FileShareState.Idle)
    val fileShareState: StateFlow<FileShareState> = _fileShareState
    private var currentFileShareToken: String? = null

    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    // File sharing UI events
    private val _fileShareUiEvents = MutableSharedFlow<FileShareUiEvent>()
    val fileShareUiEvents: SharedFlow<FileShareUiEvent> = _fileShareUiEvents.asSharedFlow()

    // Root directory URI for SAF
    private var rootDirectoryUri: Uri? = null

    init {
        connectAndObserveMessages()

        // Load saved root directory URI if available
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("file_share_prefs", Context.MODE_PRIVATE)
            val savedUriStr = prefs.getString("root_directory_uri", null)
            if (savedUriStr != null) {
                try {
                    rootDirectoryUri = Uri.parse(savedUriStr)
                    Log.d(TAG, "Loaded saved root directory URI: $rootDirectoryUri")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse saved URI", e)
                }
            }
        }

        // Specifically observe file sharing messages
        viewModelScope.launch {
            try {
                webSocketService.observeBrowseRequest().collect { browseRequest ->
                    handleBrowseRequest(browseRequest)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up file sharing message observation: ${e.message}", e)
            }
        }
    }

    private fun connectAndObserveMessages() {
        viewModelScope.launch {
            try {
                webSocketService.connectWebSocket()
                observeMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect WebSocket: ${e.localizedMessage}")
                _sessionState.value = SessionState.Error("Failed to connect WebSocket")
            }
        }
    }

    fun requestSession() {
        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                _sessionState.value = SessionState.Error("Token not found")
                return@launch
            }

            _sessionState.value = SessionState.Requesting

            // Start a timeout job
            timeoutJob = viewModelScope.launch {
                delay(30000L) // 30 seconds timeout
                if (_sessionState.value == SessionState.Requesting || _sessionState.value == SessionState.Waiting) {
                    _sessionState.value = SessionState.Timeout
                }
            }

            try {
                val from = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                webSocketService.sendSessionRequest(from, token)
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Error: ${e.localizedMessage}")
                timeoutJob?.cancel() // Cancel timeout if an error occurs
            }
        }
    }

    fun sendSessionFinalConfirmation(decision: Boolean) {
        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                _sessionState.value = SessionState.Error("Token not found")
                return@launch
            }

            val from = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            try {
                webSocketService.sendFinalConformation(from, token, decision)
                _sessionState.value = if(decision) SessionState.Connected else SessionState.Idle
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Failed to send confirmation: ${e.localizedMessage}")
            }
        }
    }

    fun disconnectSession() {
        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                _sessionState.value = SessionState.Error("Token not found")
                return@launch
            }

            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

            try {
                val response = apiService.removeSession(
                    mapOf("token" to token, "deviceId" to deviceId)
                )

                if (response.code() == 200) {
                    resetSessionState()
                } else {
                    val errorMessage = response.body()?.get("message") as? String
                        ?: "Failed to disconnect session"
                    _sessionState.value = SessionState.Error(errorMessage)
                }
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Error: ${e.localizedMessage}")
            }
        }
    }

    private fun observeMessages() {
        messageObservationJob = viewModelScope.launch {
            webSocketService.observeMessages().collect { message ->
                Log.d(TAG, "Raw message received: $message")
                try {
                    val response = JSONObject(message)
                    val messageType = response.optString("type", "")

                    when (messageType) {
                        "click" -> {
                            val payload = response.optJSONObject("payload")
                            val x = payload.getDouble("x").toFloat()
                            val y = payload.getDouble("y").toFloat()
                            //redo coordinates cuz relative
                            val context = RemoteControlAccessibilityService.instance

                            val displayMetrics = context?.resources?.displayMetrics
                            //val displayMetrics = context?.resources?.displayMetrics
                            val screenWidth = displayMetrics?.widthPixels ?: 0
                            val screenHeight = displayMetrics?.heightPixels?.plus(
                                getNavigationBarHeight(context)
                            )
                            //val screenHeight = displayMetrics?.heightPixels ?: 0
                            Log.d("Dims", "Phone dimens ($screenWidth) ($screenHeight")
                            val absoluteX = x * screenWidth
                            val absoluteY = y * screenHeight!! //2376 //screenHeight for OPPO as heightPixels cut off the NavBar

                            context.performClick(absoluteX, absoluteY)

                            Log.d("WebRTCManager", "Received case click at relative ($x, $y), ($absoluteX, $absoluteY)")
                        }

                        "session_ended" -> {
                            Log.d(TAG, "Session ended message received")
                            //handleSessionEnded()
                        }
                        "swipe" -> {
                            val payload = response.optJSONObject("payload")
                            val startX = payload.getDouble("startX").toFloat()
                            val startY = payload.getDouble("startY").toFloat()
                            val endX = payload.getDouble("endX").toFloat()
                            val endY = payload.getDouble("endY").toFloat()
                            val velocity = payload.optDouble("velocity", 1.0)

                            val context = RemoteControlAccessibilityService.instance

                            val displayMetrics = context?.resources?.displayMetrics
                            val screenWidth = displayMetrics?.widthPixels ?: 0
                            val screenHeight = displayMetrics?.heightPixels?.plus(
                                getNavigationBarHeight(context)
                            ) ?: 0

                            // Convert relative coordinates to absolute screen coordinates
                            val absoluteStartX = startX * screenWidth
                            val absoluteStartY = startY * screenHeight
                            val absoluteEndX = endX * screenWidth
                            val absoluteEndY = endY * screenHeight

                            // Calculate the distance of the swipe
                            val distance = Math.sqrt(
                                Math.pow((absoluteEndX - absoluteStartX).toDouble(), 2.0) +
                                        Math.pow((absoluteEndY - absoluteStartY).toDouble(), 2.0)
                            ).toFloat()

                            // Calculate duration based on velocity and distance
                            // Lower velocity means longer duration (slower swipe)
                            // Higher velocity means shorter duration (faster swipe)
                            // Base duration is scaled inversely by velocity with reasonable limits
                            val baseDuration = (distance / velocity).toLong()
                            val durationMs = Math.max(100, Math.min(baseDuration, 800))

                            Log.d(TAG, "Performing swipe from ($absoluteStartX, $absoluteStartY) to " +
                                    "($absoluteEndX, $absoluteEndY) with duration $durationMs ms")

                            context?.performSwipe(
                                absoluteStartX,
                                absoluteStartY,
                                absoluteEndX,
                                absoluteEndY,
                                durationMs
                            )
                        }

                        "info" -> {
                            Log.d(TAG, "Received info message")
                            _sessionState.value = SessionState.Waiting
                        }
                        "error" -> {
                            val errorMessage = response.optString("message", "Unknown error")
                            Log.e(TAG, "Received error message: $errorMessage")
                            _sessionState.value = SessionState.Error(errorMessage)
                            timeoutJob?.cancel()
                        }
                        "approved" -> {
                            Log.d(TAG, "Received approved message")
                            _sessionState.value = SessionState.Accepted
                        }
                        "rejected" -> {
                            val reason = response.optString("message", "Session rejected")
                            Log.w(TAG, "Received rejected message: $reason")
                            _sessionState.value = SessionState.Rejected
                        }
                        "session_confirmed" -> {
                            Log.d(TAG, "Server confirmed session start.")
                        }
                        "offer" -> {
                            // Critical part - need to distinguish between SDP offers and ICE candidates
                            val payload = response.optJSONObject("payload")
                            if (payload != null) {
                                // Check if this is actually an ICE candidate message
                                val parsedMessage = payload.optJSONObject("parsedMessage")
                                if (parsedMessage != null) {
                                    val innerType = parsedMessage.optString("type", "")

                                    if (innerType == "ice-candidate") {
                                        // This is actually an ICE candidate
                                        handleIceCandidate(response)
                                    } else if (innerType == "offer") {
                                        // This is a genuine SDP offer
                                        handleSdpOffer(response)
                                    } else {
                                        Log.w(TAG, "Unknown inner message type: $innerType")
                                    }
                                } else {
                                    // Direct structure without parsedMessage
                                    handleSdpOffer(response)
                                }
                            }
                        }
                        "keyboard" -> {
                            val payload = response.getJSONObject("payload")
                            val key = payload.getString("key")
                            val eventType = payload.getString("type")
                            if (eventType == "keydown") {
                                when (key) {
                                    "Backspace", "Enter" -> RemoteControlAccessibilityService.instance?.inputCharacter(key)
                                    else -> {
                                        if (key.length == 1) {
                                            RemoteControlAccessibilityService.instance?.inputCharacter(key)
                                        }
                                    }
                                }
                            }
                        }
                        "ice-candidate" -> {
                            handleIceCandidate(response)
                        }
                        "browse_request" -> {
                            val browseRequestMessage = Gson().fromJson(message, BrowseRequestMessage::class.java)
                            handleBrowseRequest(browseRequestMessage)
                        }
                        else -> {
                            Log.d(TAG, "Unhandled message type: $messageType")
                        }
                    }
                } catch (e: JSONException) {
                    Log.e(TAG, "Failed to parse WebSocket message: $message", e)
                    _sessionState.value = SessionState.Error("Failed to parse server message")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing message: ${e.message}", e)
                }
            }
        }
    }

    fun getNavigationBarHeight(context: Context): Int {
        val resources = context.resources
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    private fun handleSdpOffer(response: JSONObject) {
        try {
            val fromId = response.optString("fromId", "unknown_sender")
            val payload = response.optJSONObject("payload")

            if (payload != null) {
                val parsedMessage = payload.optJSONObject("parsedMessage")

                if (parsedMessage != null) {
                    val innerPayload = parsedMessage.optJSONObject("payload")

                    if (innerPayload != null && innerPayload.has("sdp")) {
                        val sdp = innerPayload.getString("sdp")
                        Log.d(TAG, "Found SDP in nested payload structure")
                        webRTCManager.confirmSessionAndStartStreaming(fromId, sdp)
                        return
                    }
                }

                // Check direct structure just in case
                if (payload.has("sdp")) {
                    val sdp = payload.getString("sdp")
                    Log.d(TAG, "Found SDP directly in payload")
                    webRTCManager.confirmSessionAndStartStreaming(fromId, sdp)
                    return
                }
            }

            Log.e(TAG, "SDP not found in offer message: $payload")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SDP offer: ${e.message}", e)
            _sessionState.value = SessionState.Error("Error processing offer: ${e.message}")
        }
    }

    private fun handleIceCandidate(response: JSONObject) {
        try {
            val fromId = response.optString("fromId", "unknown_sender")
            val payload = response.optJSONObject("payload")

            if (payload != null) {
                val parsedMessage = payload.optJSONObject("parsedMessage")

                if (parsedMessage != null) {
                    val innerPayload = parsedMessage.optJSONObject("payload")

                    if (innerPayload != null) {
                        val candidate = innerPayload.optString("candidate", "")
                        val sdpMid = innerPayload.optString("sdpMid", "")
                        val sdpMLineIndex = innerPayload.optInt("sdpMLineIndex", 0)

                        // Only forward if not an empty candidate
                        if (candidate.isNotEmpty()) {
                            Log.d(TAG, "Processing ICE candidate")
                            try {
                                webRTCManager.handleRtcMessage("ice-candidate", fromId, innerPayload)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing ICE candidate", e)
                            }
                        } else {
                            Log.d(TAG, "Received empty ICE candidate (end of candidates)")
                        }
                        return
                    }
                }

                // Check direct structure as well
                val candidate = payload.optString("candidate", "")
                if (candidate.isNotEmpty()) {
                    Log.d(TAG, "Processing ICE candidate from direct payload")
                    try {
                        webRTCManager.handleRtcMessage("ice-candidate", fromId, payload)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing direct ICE candidate", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ICE candidate: ${e.message}", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startStreaming(resultCode: Int, data: Intent, fromId: String) {
        viewModelScope.launch {
            try {
                val intent = ScreenSharingService.getStartIntent(context, resultCode, data, fromId)
                context.startForegroundService(intent)
                _sessionState.value = SessionState.Streaming
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Failed to start streaming: ${e.localizedMessage}")
            }
        }
    }

    fun startObservingRtcMessages(lifecycleOwner: LifecycleOwner) {
        viewModelScope.launch {
            webRTCManager.startObservingRtcMessages(lifecycleOwner)
        }
    }

    fun resetSessionState() {
        _sessionState.value = SessionState.Idle
        timeoutJob?.cancel()
    }

    fun stopObservingMessages() {
        messageObservationJob?.cancel()
        messageObservationJob = null
    }

    private var fileShareTimeoutJob: Job? = null

    // Request the user to select a directory
    fun requestDirectoryAccess() {
        viewModelScope.launch {
            _fileShareUiEvents.emit(FileShareUiEvent.RequestDirectoryPicker)
        }
    }

    // Set the selected directory URI
    fun setRootDirectoryUri(uri: Uri) {
        rootDirectoryUri = uri
        // Save the URI to preferences for persistence
        val prefs = context.getSharedPreferences("file_share_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("root_directory_uri", uri.toString()).apply()

        // Notify UI that directory is ready
        viewModelScope.launch {
            _fileShareUiEvents.emit(FileShareUiEvent.DirectorySelected(uri))
        }
    }

    // Handle browse request with SAF
    private fun handleBrowseRequest(message: BrowseRequestMessage) {
        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                Log.e(TAG, "FileShare: Token not found. Cannot browse.")
                return@launch
            }

            Log.i(TAG, "FileShare: Received browse_request for path: ${message.path} in session (token ${token})")

            // Check if root directory is selected
            if (rootDirectoryUri == null) {
                Log.e(TAG, "FileShare: Root directory not selected. Requesting directory selection.")
                // Request directory selection
                _fileShareUiEvents.emit(FileShareUiEvent.RequestDirectoryPicker)
                // Send empty response for now
                webSocketService.sendBrowseResponse(deviceId, token, message.path, emptyList())
                return@launch
            }

            try {
                // Get directory contents using SAF
                val entries = listDirectorySAF(message.path)

                Log.d(TAG, "FileShare: Found ${entries.size} entries for path ${message.path}")

                // Send the response
                webSocketService.sendBrowseResponse(
                    deviceId,
                    token,
                    message.path,
                    entries
                )

                Log.d(TAG, "FileShare: Browse response sent successfully with ${entries.size} entries")
            } catch (e: Exception) {
                Log.e(TAG, "FileShare: Error browsing path ${message.path}", e)
                webSocketService.sendBrowseResponse(deviceId, token, message.path, emptyList())
            }
        }
    }

    // List directory contents using DocumentFile and SAF
    private suspend fun listDirectorySAF(relativePath: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val currentRootUri = rootDirectoryUri
        if (currentRootUri == null) {
            Log.w(TAG, "FileShare: Root directory URI not set. Cannot list contents.")
            return@withContext emptyList()
        }

        Log.d(TAG, "FileShare: Listing directory relative path: $relativePath using root URI: $currentRootUri")

        var targetDocFile = DocumentFile.fromTreeUri(context, currentRootUri)

        if (targetDocFile == null || !targetDocFile.exists()) {
            Log.e(TAG, "FileShare: Could not access root directory: $currentRootUri")
            return@withContext emptyList()
        }

        // Navigate to the specified relative path if it's not the root
        if (relativePath != "/" && relativePath.isNotEmpty()) {
            val pathSegments = relativePath.trim('/').split('/')
            for (segment in pathSegments) {
                if (segment.isNotEmpty()) {
                    val childDoc = targetDocFile?.findFile(segment)
                    if (childDoc == null || !childDoc.exists()) {
                        Log.w(TAG, "FileShare: Path segment not found: $segment in $relativePath")
                        return@withContext emptyList()
                    }
                    targetDocFile = childDoc
                }
            }
        }

        if (targetDocFile == null || !targetDocFile.isDirectory) {
            Log.w(TAG, "FileShare: Target path is not a directory: $relativePath")
            return@withContext emptyList()
        }

        Log.d(TAG, "FileShare: Listing contents of: ${targetDocFile.uri}")

        // List all files in the directory
        val entries = mutableListOf<FileEntry>()

        targetDocFile.listFiles().forEach { file ->
            val name = file.name ?: "Unknown"
            val type = if (file.isDirectory) "folder" else "file"
            val size = if (file.isFile) file.length() else null

            entries.add(FileEntry(name = name, type = type, size = size))
        }

        Log.d(TAG, "FileShare: Found ${entries.size} entries")
        return@withContext entries
    }

    // For legacy purposes, keep the old method but call the SAF method
    private suspend fun listDirectoryContents(relativePath: String): List<FileEntry> {
        return listDirectorySAF(relativePath)
    }

    private fun handleUploadFilesToAndroid(message: UploadFilesMessage) {
        // Implementation for handling upload - would use DocumentFile for SAF
    }

    private fun handleDownloadRequestFromAndroid(message: DownloadRequestMessage) {
        // Implementation for handling download - would use DocumentFile for SAF
    }

    fun terminateFileShareSession(reason: String = "User terminated") {
        // Implementation for terminating file share session
    }

    // Recursive helper for zipping (from previous response)
    @Throws(IOException::class)
    private fun addFileEntryToZipRecursive(fileToAdd: File, entryNameInZip: String, zos: ZipOutputStream) {
        if (fileToAdd.isDirectory) {
            // Ensure directory entries end with a slash
            val dirEntryName = if (entryNameInZip.endsWith("/")) entryNameInZip else "$entryNameInZip/"
            if (dirEntryName.isNotEmpty()) { // Don't add entry for the root if entryNameInZip was ""
                zos.putNextEntry(java.util.zip.ZipEntry(dirEntryName))
                zos.closeEntry()
            }
            Log.d(TAG, "FileShare: Added directory to ZIP: $dirEntryName")
            fileToAdd.listFiles()?.forEach { childFile ->
                // Construct child entry name: if dirEntryName is "folder/", child is "file.txt", then "folder/file.txt"
                addFileEntryToZipRecursive(childFile, dirEntryName + childFile.name, zos)
            }
        } else { // It's a file
            FileInputStream(fileToAdd).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    val fileEntry = java.util.zip.ZipEntry(entryNameInZip)
                    zos.putNextEntry(fileEntry)
                    bis.copyTo(zos)
                    zos.closeEntry()
                    Log.d(TAG, "FileShare: Added file to ZIP: $entryNameInZip, size: ${fileToAdd.length()}")
                }
            }
        }
    }

    private fun ensureDirectoryExists(path: String) {
        val dir = File(path)
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.w(TAG, "FileShare: Failed to create directory: $path")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopObservingMessages() // Screen share general messages
        timeoutJob?.cancel() // Screen share session request timeout
        terminateFileShareSession("ViewModel cleared")
    }
}

sealed class SessionState {
    object Idle : SessionState()
    object Requesting : SessionState()
    object Timeout : SessionState()
    object Waiting : SessionState()
    object Accepted : SessionState()
    object Rejected : SessionState()
    object Connected : SessionState()
    data class Error(val message: String) : SessionState()
    object Streaming : SessionState()
}

// FileShareState sealed class
sealed class FileShareState {
    object Idle : FileShareState()
    object Requesting : FileShareState()
    data class SessionDecisionPending(val tokenForSession: String) : FileShareState()
    data class Active(val tokenForSession: String) : FileShareState()
    data class Browsing(val tokenForSession: String, val path: String) : FileShareState()
    data class UploadingToAndroid(val tokenForSession: String, val downloadUrl: String, val targetPathOnAndroid: String) : FileShareState()
    data class PreparingDownloadFromAndroid(val tokenForSession: String, val paths: List<String>) : FileShareState()
    data class ReadyForDownloadFromAndroid(val tokenForSession: String, val androidHostedUrl: String) : FileShareState()
    data class Error(val message: String, val tokenForSession: String? = null) : FileShareState()
    object Terminated : FileShareState()
}

// File share UI events
sealed class FileShareUiEvent {
    object RequestDirectoryPicker : FileShareUiEvent()
    data class DirectorySelected(val uri: Uri) : FileShareUiEvent()
}