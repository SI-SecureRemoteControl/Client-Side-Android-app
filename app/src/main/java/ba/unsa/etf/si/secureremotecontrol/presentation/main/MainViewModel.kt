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
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.LifecycleOwner
import ba.unsa.etf.si.secureremotecontrol.service.RemoteControlAccessibilityService
import ba.unsa.etf.si.secureremotecontrol.service.ScreenSharingService
import okhttp3.OkHttpClient
import org.json.JSONException
import ba.unsa.etf.si.secureremotecontrol.data.fileShare.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
    private var currentFileShareToken: String? = null // This will store the token used as sessionId
    private var fileShareMessageObservationJob: Job? = null
    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    init {
        connectAndObserveMessages()
        startObservingFileShareMessages()

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
    /*fun requestFileShareSession() {
        if (_fileShareState.value !is FileShareState.Idle &&
            _fileShareState.value !is FileShareState.Error &&
            _fileShareState.value !is FileShareState.Terminated) {
            Log.w(TAG, "FileShare: Request ignored, a file share session is already active or pending.")
            _fileShareState.value = FileShareState.Error("Another file share session is active or pending.")
            return
        }

        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                Log.e(TAG, "FileShare: Token not found. Cannot request file share session.")
                _fileShareState.value = FileShareState.Error("Authentication token not found.")
                return@launch
            }

            currentFileShareToken = token // Store the token to be used as sessionId
            _fileShareState.value = FileShareState.Requesting
            try {
                // The 'token' is now sent as the 'sessionId' argument
                webSocketService.sendRequestSessionFileshare(deviceId, token)
                _fileShareState.value = FileShareState.SessionDecisionPending(token)
                Log.i(TAG, "FileShare: Sent request_session_fileshare with deviceId '$deviceId' and sessionId (token) '$token'")
            } catch (e: Exception) {
                Log.e(TAG, "FileShare: Failed to send request_session_fileshare", e)
                _fileShareState.value = FileShareState.Error("Failed to request file share session: ${e.message}", token)
                currentFileShareToken = null
            }
        }
    }*/

    fun requestFileShareSession() {
        if (_fileShareState.value !is FileShareState.Idle &&
            _fileShareState.value !is FileShareState.Error &&
            _fileShareState.value !is FileShareState.Terminated) {
            Log.w(TAG, "FileShare: Request ignored, a file share session is already active or pending.")
            _fileShareState.value = FileShareState.Error("Another file share session is active or pending.")
            return
        }

        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                Log.e(TAG, "FileShare: Token not found. Cannot request file share session.")
                _fileShareState.value = FileShareState.Error("Authentication token not found.")
                return@launch
            }

            currentFileShareToken = token // Store the token to be used as sessionId
            _fileShareState.value = FileShareState.Requesting

            // Start the timeout job
            fileShareTimeoutJob?.cancel() // Cancel any existing timeout job
            fileShareTimeoutJob = viewModelScope.launch {
                delay(30000L) // 30 seconds timeout
                if (_fileShareState.value == FileShareState.Requesting) {
                    Log.w(TAG, "FileShare: Request timed out. Terminating session.")
                    _fileShareState.value = FileShareState.Terminated
                }
            }

            try {
                webSocketService.sendRequestSessionFileshare(deviceId, token)
                Log.d(TAG, "FileShare: Request sent successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "FileShare: Failed to send request.", e)
                _fileShareState.value = FileShareState.Error("Failed to send file share request.")
                fileShareTimeoutJob?.cancel() // Cancel the timeout if an error occurs
            }
        }
    }


    private fun handleFileShareResponse(decision: Boolean) {
        fileShareTimeoutJob?.cancel() // Cancel the timeout job
        if (decision) {
            _fileShareState.value = FileShareState.Active(currentFileShareToken ?: "unknown_token")
        } else {
            _fileShareState.value = FileShareState.Terminated
        }
    }

    private fun startObservingFileShareMessages() {
        fileShareMessageObservationJob?.cancel()
        fileShareMessageObservationJob = viewModelScope.launch {
            launch {
                webSocketService.observeDecisionFileShare().collect { message ->
                    Log.d(TAG, "FileShare: Received decision_fileshare: $message")
                    if (message.deviceId == deviceId && message.sessionId == currentFileShareToken) {
                        handleFileShareResponse(message.decision)
                    } else {
                        Log.w(TAG, "FileShare: Mismatched decision_fileshare. Expected token: $currentFileShareToken, Got: ${message.sessionId}")
                    }
                }
            }
            launch {
                webSocketService.observeBrowseRequest().collect { message ->
                    Log.d(TAG, "FileShare: Received browse_request: $message")
                    if (message.deviceId == deviceId && message.sessionId == currentFileShareToken) {
                        handleBrowseRequest(message)
                    } else {
                        Log.w(TAG, "FileShare: Mismatched browse_request. Expected token: $currentFileShareToken, Got: ${message.sessionId}")
                    }
                }
            }
            launch {
                webSocketService.observeUploadFiles().collect { message ->
                    Log.d(TAG, "FileShare: Received upload_files (for Android to download): $message")
                    if (message.deviceId == deviceId && message.sessionId == currentFileShareToken) {
                        handleUploadFilesToAndroid(message)
                    } else {
                        Log.w(TAG, "FileShare: Mismatched upload_files. Expected token: $currentFileShareToken, Got: ${message.sessionId}")
                    }
                }
            }
            launch {
                webSocketService.observeDownloadRequest().collect { message ->
                    Log.d(TAG, "FileShare: Received download_request (for Android to prepare ZIP): $message")
                    if (message.deviceId == deviceId && message.sessionId == currentFileShareToken) {
                        handleDownloadRequestFromAndroid(message)
                    } else {
                        Log.w(TAG, "FileShare: Mismatched download_request. Expected token: $currentFileShareToken, Got: ${message.sessionId}")
                    }
                }
            }
        }
        Log.d(TAG, "FileShare: Started observing file share specific messages.")
    }

    private fun handleUploadFilesToAndroid(message: UploadFilesMessage){

    }
    private fun handleBrowseRequest(message: BrowseRequestMessage) {
        val token = currentFileShareToken ?: return // Should not happen if checks are in place
        _fileShareState.value = FileShareState.Browsing(token, message.path)
        Log.i(TAG, "FileShare: Received browse_request for path: ${message.path} in session (token ${token})")
        viewModelScope.launch {
            try {
                val entries = listDirectoryContents(message.path) // Runs on Dispatchers.IO
                webSocketService.sendBrowseResponse(
                    deviceId,
                    token, // Send token as sessionId
                    message.path,
                    entries
                )
                if (_fileShareState.value is FileShareState.Browsing) {
                    _fileShareState.value = FileShareState.Active(token)
                }
            } catch (e: Exception) {
                Log.e(TAG, "FileShare: Error browsing path ${message.path}", e)
                webSocketService.sendBrowseResponse(deviceId, token, message.path, emptyList())
                _fileShareState.value = FileShareState.Error("Error browsing: ${e.message}", token)
            }
        }
    }



    // Helper to resolve the target path on Android.
    // This needs careful consideration for security and permissions.
    private fun resolvePathOnAndroid(relativePath: String): File {
        // Example: Resolve relative to app's external files directory.
        // IMPORTANT: If relativePath can be "../.." or absolute, this can be a security risk.
        // Sanitize or restrict relativePath.
        val sanitizedPath = relativePath.removePrefix("/").removePrefix("./") // Basic sanitization
        val baseDir = context.getExternalFilesDir("shared_content") ?: context.filesDir.resolve("shared_content")
        ensureDirectoryExists(baseDir.absolutePath)
        return File(baseDir, sanitizedPath)
    }


    private suspend fun downloadAndUnzipFiles(downloadUrl: String, destinationDirectoryPath: File): Boolean = withContext(
        Dispatchers.IO) {
        Log.i(TAG, "FileShare: Attempting to download from $downloadUrl to ${destinationDirectoryPath.absolutePath}")
        // Using the injected OkHttpClient
        val request = okhttp3.Request.Builder().url(downloadUrl).build()
        val tempZipFile = File(context.cacheDir, "download_${System.currentTimeMillis()}_${Thread.currentThread().id}.zip")

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "FileShare: Download failed - HTTP ${response.code} from $downloadUrl")
                    return@withContext false
                }
                response.body?.byteStream()?.use { inputStream ->
                    FileOutputStream(tempZipFile).use { fileOut -> inputStream.copyTo(fileOut) }
                } ?: return@withContext false
            }
            Log.i(TAG, "FileShare: ZIP downloaded to ${tempZipFile.absolutePath}")

            // Unzip directly into the destinationDirectoryPath
            ensureDirectoryExists(destinationDirectoryPath.absolutePath)
            ZipInputStream(BufferedInputStream(FileInputStream(tempZipFile))).use { zis ->
                var zipEntry: java.util.zip.ZipEntry? = zis.nextEntry
                while (zipEntry != null) {
                    val newFile = File(destinationDirectoryPath, zipEntry.name)
                    if (!newFile.canonicalPath.startsWith(destinationDirectoryPath.canonicalPath + File.separator)) {
                        throw SecurityException("Zip Slip vulnerability: ${zipEntry.name}")
                    }
                    if (zipEntry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    zipEntry = zis.nextEntry
                }
            }
            Log.i(TAG, "FileShare: ZIP unzipped successfully into ${destinationDirectoryPath.absolutePath}")
            return@withContext true
        } catch (e: Exception) { // Catch generic Exception for robustness
            Log.e(TAG, "FileShare: Exception during download/unzip for $downloadUrl to $destinationDirectoryPath", e)
            return@withContext false
        } finally {
            tempZipFile.delete()
        }
    }


    private fun handleDownloadRequestFromAndroid(message: DownloadRequestMessage) {

    }

    fun terminateFileShareSession(reason: String = "User terminated") {

    }

    // --- File System Utility Methods (listDirectoryContents, createZipFromPaths, etc.) ---
    // (These would be similar to previous answers, ensure they use Dispatchers.IO for blocking ops)
    // Ensure `createZipFromPaths` and `listDirectoryContents` correctly interpret paths relative to a defined root.

    private suspend fun listDirectoryContents(relativePath: String): List<FileEntry> = withContext(Dispatchers.IO) {
        Log.d(TAG, "FileShare: Listing directory relative path: $relativePath")
        // Define a base directory. This needs to be consistent with what the Web client expects.
        // Example: App's external files directory.
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val targetDir = if (relativePath == "/" || relativePath.isEmpty()) baseDir else File(baseDir, relativePath)

        if (!targetDir.exists() || !targetDir.isDirectory) {
            Log.w(TAG, "FileShare: Path does not exist or is not a directory: ${targetDir.absolutePath}")
            return@withContext emptyList()
        }
        Log.d(TAG, "FileShare: Listing absolute path: ${targetDir.absolutePath}")

        targetDir.listFiles()?.mapNotNull { file ->
            FileEntry(
                name = file.name,
                type = if (file.isDirectory) "folder" else "file",
                size = if (file.isFile) file.length() else null
            )
        } ?: emptyList()
    }



    // Recursive helper for zipping (from previous response)
    @Throws(IOException::class)
    private fun addFileEntryToZipRecursive(fileToAdd: File, entryNameInZip: String, zos: ZipOutputStream) {
        // ... (implementation from previous answer: ensure entryNameInZip is correctly constructed for sub-files/dirs)
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
        // ... (Implementation from previous answer) ...
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
        fileShareMessageObservationJob?.cancel()
        fileShareMessageObservationJob = null
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

// FileShareState sealed class (as defined in previous answers)
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


