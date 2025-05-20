package ba.unsa.etf.si.secureremotecontrol.presentation.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import ba.unsa.etf.si.secureremotecontrol.data.fileShare.*
import ba.unsa.etf.si.secureremotecontrol.data.fileShare.DownloadRequestMessage
import ba.unsa.etf.si.secureremotecontrol.data.network.ApiService
import ba.unsa.etf.si.secureremotecontrol.data.webrtc.WebRTCManager
import ba.unsa.etf.si.secureremotecontrol.service.RemoteControlAccessibilityService
import ba.unsa.etf.si.secureremotecontrol.service.ScreenSharingService
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

data class UploadFilesMessage(
    @SerializedName("type") val type: String = "upload_files",
    @SerializedName("deviceId") val fromDeviceId: String,
    @SerializedName("sessionId") val sessionId: String?,
    @SerializedName("downloadUrl") val downloadUrl: String,
    @SerializedName("remotePath") val remotePath: String,
)





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

    // Replace SharedFlow with LiveData for file share UI events
    private val _fileShareUiEvents = MutableLiveData<FileShareUiEvent>()
    val fileShareUiEvents: LiveData<FileShareUiEvent> = _fileShareUiEvents

    // We'll still keep track of the last browse path for file operations
    private var lastSuccessfulBrowsePathOnAndroid: String = "/"

    init {
        connectAndObserveMessages()

        // Observe file sharing specific messages (like browse_request)
        viewModelScope.launch {
            try {
                webSocketService.observeBrowseRequest().collect { browseRequest ->
                    handleBrowseRequest(browseRequest)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up file sharing message observation: ${e.message}", e)
            }
        }

        viewModelScope.launch {
            try {
                webSocketService.observeDownloadRequest().collect { downloadRequest ->
                    handleDownloadRequest(downloadRequest)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up download request observation: ${e.message}", e)
            }
        }
    }

    private fun hasManageExternalStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // For older versions, check WRITE_EXTERNAL_STORAGE
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
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
            timeoutJob = viewModelScope.launch {
                delay(30000L)
                if (_sessionState.value == SessionState.Requesting || _sessionState.value == SessionState.Waiting) {
                    _sessionState.value = SessionState.Timeout
                }
            }

            try {
                webSocketService.sendSessionRequest(deviceId, token)
            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("Error: ${e.localizedMessage}")
                timeoutJob?.cancel()
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
            try {
                webSocketService.sendFinalConformation(deviceId, token, decision)
                _sessionState.value = if (decision) SessionState.Connected else SessionState.Idle
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
            try {
                val response = apiService.removeSession(mapOf("token" to token, "deviceId" to deviceId))
                if (response.code() == 200) {
                    resetSessionState()
                } else {
                    val errorMessage = response.body()?.get("message") as? String ?: "Failed to disconnect session"
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
                            val payload = response.optJSONObject("payload") ?: return@collect
                            val x = payload.getDouble("x").toFloat()
                            val y = payload.getDouble("y").toFloat()
                            val accessibilityService = RemoteControlAccessibilityService.instance ?: return@collect
                            val displayMetrics = accessibilityService.resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels
                            val screenHeight = displayMetrics.heightPixels + getNavigationBarHeight(accessibilityService)
                            val absoluteX = x * screenWidth
                            val absoluteY = y * screenHeight
                            accessibilityService.performClick(absoluteX, absoluteY)
                            Log.d(TAG, "Click at ($absoluteX, $absoluteY)")
                        }
                        "swipe" -> {
                            val payload = response.optJSONObject("payload") ?: return@collect
                            val accessibilityService = RemoteControlAccessibilityService.instance ?: return@collect
                            val displayMetrics = accessibilityService.resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels
                            val screenHeight = displayMetrics.heightPixels + getNavigationBarHeight(accessibilityService)

                            val startX = (payload.getDouble("startX") * screenWidth).toFloat()
                            val startY = (payload.getDouble("startY") * screenHeight).toFloat()
                            val endX = (payload.getDouble("endX") * screenWidth).toFloat()
                            val endY = (payload.getDouble("endY") * screenHeight).toFloat()
                            val velocity = payload.optDouble("velocity", 1.0)
                            val distance = Math.sqrt(Math.pow((endX - startX).toDouble(), 2.0) + Math.pow((endY - startY).toDouble(), 2.0)).toFloat()
                            val baseDuration = (distance / velocity).toLong()
                            val durationMs = Math.max(100, Math.min(baseDuration, 800))
                            accessibilityService.performSwipe(startX, startY, endX, endY, durationMs)
                            Log.d(TAG, "Swipe from ($startX, $startY) to ($endX, $endY) duration $durationMs ms")
                        }
                        "info" -> {
                            if( _sessionState.value != SessionState.Streaming)
                            _sessionState.value = SessionState.Waiting
                        }
                        "error" -> {
                            val errorMessage = response.optString("message", "Unknown error")
                            Log.e(TAG, "Received error: $errorMessage")
                            _sessionState.value = SessionState.Error(errorMessage)
                            timeoutJob?.cancel()
                        }
                        "approved" -> _sessionState.value = SessionState.Accepted
                        "rejected" -> {
                            val reason = response.optString("message", "Session rejected")
                            Log.w(TAG, "Session rejected: $reason")
                            _sessionState.value = SessionState.Rejected
                        }
                        "session_confirmed" -> Log.d(TAG, "Server confirmed session start.")
                        "offer", "ice-candidate" -> handleWebRtcSignaling(response)
                        "keyboard" -> {
                            val payload = response.optJSONObject("payload") ?: return@collect
                            val key = payload.getString("key")
                            if (payload.getString("type") == "keydown") {
                                RemoteControlAccessibilityService.instance?.inputCharacter(key)
                            }
                        }

                        "session_ended" ->{
                            Log.d(TAG, "Session ended by server.")
                            disconnectSession()
                            _sessionState.value = SessionState.Idle
                            webRTCManager.stopScreenCapture()
                            webRTCManager.release()
                            timeoutJob?.cancel()
                        }
                        "browse_request" -> {
                            Log.d(TAG, "Browse request received (also handled by dedicated observer).")
                        }
                        "upload_files" -> {
                            Log.d(TAG, "Received 'upload_files' message type.")
                            try {
                                val uploadMessage = Gson().fromJson(message, UploadFilesMessage::class.java)
                                handleUploadFilesToAndroid(uploadMessage)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing UploadFilesMessage: $message", e)
                            }
                        }
                        else -> Log.d(TAG, "Unhandled message type: $messageType")
                    }
                } catch (e: JSONException) {
                    Log.e(TAG, "Failed to parse WebSocket message: $message", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing message: ${e.message}", e)
                }
            }
        }
    }

    private fun handleWebRtcSignaling(response: JSONObject) {
        val fromId = response.optString("fromId", "unknown_sender")
        val payload = response.optJSONObject("payload") ?: return
        val messageType = response.optString("type") // "offer" or "ice-candidate"

        // Potentially nested structure
        val actualPayload = payload.optJSONObject("parsedMessage")?.optJSONObject("payload") ?: payload

        when (messageType) {
            "offer" -> {
                if (actualPayload.has("sdp")) {
                    val sdp = actualPayload.getString("sdp")
                    Log.d(TAG, "Handling SDP offer from $fromId")
                    webRTCManager.confirmSessionAndStartStreaming(fromId, sdp)
                } else {
                    Log.e(TAG, "SDP not found in offer: $actualPayload")
                }
            }
            "ice-candidate" -> {
                val candidate = actualPayload.optString("candidate")
                if (candidate.isNotEmpty()) {
                    Log.d(TAG, "Handling ICE candidate from $fromId")
                    webRTCManager.handleRtcMessage("ice-candidate", fromId, actualPayload)
                } else {
                    Log.d(TAG, "Received empty ICE candidate from $fromId (end of candidates).")
                }
            }
            else -> Log.w(TAG, "Unknown WebRTC signaling type: $messageType")
        }
    }

    fun getNavigationBarHeight(context: Context): Int {
        val resources = context.resources
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startStreaming(resultCode: Int, data: Intent, fromId: String) {
        Log.d(TAG, "startStreaming called with resultCode $resultCode for device $fromId")
        if (_sessionState.value is SessionState.Streaming) {
            Log.d(TAG, "Already streaming, skipping startStreaming.")
            return
        }
        viewModelScope.launch {
            try {
                // First start the WebRTC components directly
                try {
                    Log.d(TAG, "Initializing WebRTC with resultCode and data")
                    webRTCManager.startScreenCapture(resultCode, data, fromId)
                    Log.d(TAG, "WebRTC screen capture initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR: Failed to initialize WebRTC directly", e)
                    // Continue anyway to try service method
                }

                // Then start the foreground service
                val intent = ScreenSharingService.getStartIntent(context, resultCode, data, fromId)
                Log.d(TAG, "Starting screen sharing service with intent")
                context.startForegroundService(intent)
                Log.d(TAG, "Screen sharing service started successfully")

                // Update session state
                if( _sessionState.value != SessionState.Streaming) {
                    _sessionState.value = SessionState.Streaming
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in startStreaming", e)

                _sessionState.value = SessionState.Error("Failed to start streaming: ${e.localizedMessage}")
            }
        }
    }

    // Add a method to notify when screen sharing has started
    fun notifyScreenSharingStarted(fromId: String) {
        _sessionState.value = SessionState.Streaming
        Log.d(TAG, "Screen sharing started for device: $fromId")
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

    // --- File Sharing Logic ---

    // Changed to request ALL FILES ACCESS instead of directory picker
    fun requestDirectoryAccess() {
        _fileShareUiEvents.postValue(FileShareUiEvent.PermissionOrDirectoryNeeded)
        Log.d(TAG, "Requesting All Files Access permission")
    }

    // This method will still exist but will also request All Files Access
    fun requestDirectoryAccessViaPicker() {
        _fileShareUiEvents.postValue(FileShareUiEvent.RequestDirectoryPicker)
        Log.d(TAG, "Requesting All Files Access permission (via picker event)")
    }

    // Not needed anymore since we prefer All Files Access over SAF
    fun setSafRootDirectoryUri(uri: Uri) {
        Log.i(TAG, "SAF Root Directory URI method called but not needed anymore")
        // Notify we're ready to start screen capture if needed
        _fileShareUiEvents.postValue(FileShareUiEvent.DirectorySelected(uri))
    }

    private fun handleBrowseRequest(message: BrowseRequestMessage) {
        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                Log.e(TAG, "FileShare: Token not found. Cannot browse.")
                return@launch
            }
            Log.i(TAG, "FileShare: Received browse_request for path: ${message.path} in session (token ${token})")
            _sessionState.value = SessionState.Streaming
            try {
                val entries = listDirectoryContents(message.path)
                if (entries == null && !hasManageExternalStoragePermission()) {
                    // No access - request All Files Access
                    Log.w(TAG, "FileShare: No permission for file access. MANAGE_EXTERNAL_STORAGE not granted.")
                    // Post event to request All Files Access
                    _fileShareUiEvents.postValue(FileShareUiEvent.PermissionOrDirectoryNeeded)
                    webSocketService.sendBrowseResponse(deviceId, token, message.path, emptyList())
                    return@launch
                }

                webSocketService.sendBrowseResponse(deviceId, token, message.path, entries ?: emptyList())
                Log.d(TAG, "FileShare: Browse response sent for path ${message.path} with ${entries?.size ?: 0} entries.")
                Log.w("Tag", "Session state: ${_sessionState.value}")
            } catch (e: Exception) {
                Log.e(TAG, "FileShare: Error browsing path ${message.path}", e)
                webSocketService.sendBrowseResponse(deviceId, token, message.path, emptyList())
            }
        }
    }


    private suspend fun handleUploadFilesToAndroid(message: UploadFilesMessage) = withContext(Dispatchers.IO) {
        Log.i(TAG, "FileShare: DOWNLOAD_TO_ANDROID started. URL: ${message.downloadUrl}, Web's remotePath: '${message.remotePath}', Last browsed Android base: '$lastSuccessfulBrowsePathOnAndroid'")

        val name= message.downloadUrl.split('/').last();

        _fileShareState.value = FileShareState.UploadingToAndroid(
            tokenForSession = message.sessionId ?: currentFileShareToken ?: "unknown",
            downloadUrl = message.downloadUrl,
            targetPathOnAndroid = lastSuccessfulBrowsePathOnAndroid
        )

        val tempZipFile = File(context.cacheDir, "server_download_${System.currentTimeMillis()}.zip")
        val sessionId = message.sessionId ?: currentFileShareToken ?: "unknown"

        try {
            // 1. Download the ZIP file from the server
            Log.d(TAG, "FileShare: Downloading from ${message.downloadUrl} to ${tempZipFile.absolutePath}")
            val request = Request.Builder().url(message.downloadUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "FileShare: Download from server failed. Code: ${response.code}, Message: ${response.message}")
                    _fileShareState.value = FileShareState.Error("Download failed: ${response.code} ${response.message}")

                   val name= message.downloadUrl.split('/').last();
                    // Send failure status
                    webSocketService.sendUploadStatus(
                        deviceId = deviceId,
                        sessionId = sessionId,
                        status = "error",
                        message = "Failed to download file: ${response.code} ${response.message}",
                        path = message.remotePath,
                        fileName = name
                    )

                    return@withContext
                }
                response.body?.byteStream()?.use { inputStream ->
                    FileOutputStream(tempZipFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: run {
                    Log.e(TAG, "FileShare: Download from server failed. Response body is null.")
                    _fileShareState.value = FileShareState.Error("Download failed: Empty response from server")

                    // Send failure status
                    webSocketService.sendUploadStatus(
                        deviceId = deviceId,
                        sessionId = sessionId,
                        status = "error",
                        message = "Download failed: Empty response from server",
                        path = message.remotePath,
                        fileName = name
                    )

                    return@withContext
                }
            }
            Log.d(TAG, "FileShare: Download from server complete. Size: ${tempZipFile.length()} bytes")

            // 2. Determine final target directory on Android and extract
            if (hasManageExternalStoragePermission()) {
                val androidStorageRoot = Environment.getExternalStorageDirectory()

                // FIXED: Simplified path construction to avoid duplication
                // Get the last browse path without any additional processing
                val targetPath = if (lastSuccessfulBrowsePathOnAndroid == "/" || lastSuccessfulBrowsePathOnAndroid.isEmpty()) {
                    androidStorageRoot
                } else {
                    File(androidStorageRoot, lastSuccessfulBrowsePathOnAndroid.trimStart('/'))
                }

                if (!targetPath.exists() && !targetPath.mkdirs()) {
                    Log.e(TAG, "FileShare: Could not create target directory (direct): ${targetPath.absolutePath}")
                    _fileShareState.value = FileShareState.Error("Failed to create target directory on Android")

                    // Send failure status
                    webSocketService.sendUploadStatus(
                        deviceId = deviceId,
                        sessionId = sessionId,
                        status = "error",
                        message = "Failed to create target directory on Android",
                        path = message.remotePath,
                        fileName = name
                    )

                    return@withContext
                }

                Log.i(TAG, "FileShare: Extracting downloaded ZIP (direct access) to: ${targetPath.absolutePath}")
                extractZip(tempZipFile, targetPath)
                _fileShareState.value = FileShareState.Active(sessionId)
                Log.i(TAG, "FileShare: Files downloaded and extracted successfully to (direct): ${targetPath.absolutePath}")

                // Send SUCCESS status message to comm layer
                webSocketService.sendUploadStatus(
                    deviceId = deviceId,
                    sessionId = sessionId,
                    status = "success",
                    message = "Files received successfully at ${lastSuccessfulBrowsePathOnAndroid}",
                    path= lastSuccessfulBrowsePathOnAndroid,
                    fileName = name
                )

                withContext(Dispatchers.Main) { Toast.makeText(context, "Files received into: ${lastSuccessfulBrowsePathOnAndroid}", Toast.LENGTH_LONG).show() }
            } else {
                // If we don't have All Files Access, request it
                Log.e(TAG, "FileShare: No All Files Access permission available.")
                _fileShareState.value = FileShareState.Error("No permission to save downloaded files")

                // Send failure status
                webSocketService.sendUploadStatus(
                    deviceId = deviceId,
                    sessionId = sessionId,
                    status = "error",
                    message = "No permission to save files on Android device",
                    path= lastSuccessfulBrowsePathOnAndroid,
                    fileName = name
                )

                _fileShareUiEvents.postValue(FileShareUiEvent.PermissionOrDirectoryNeeded)
                return@withContext
            }
        } catch (e: IOException) {
            Log.e(TAG, "FileShare: IOException during file download/extraction from server", e)
            _fileShareState.value = FileShareState.Error("IO Error: ${e.message}")

            // Send failure status
            webSocketService.sendUploadStatus(
                deviceId = deviceId,
                sessionId = sessionId,
                status = "error",
                message = "IO Error: ${e.message}",
                path= lastSuccessfulBrowsePathOnAndroid,
                fileName = name
            )
        } catch (e: Exception) {
            Log.e(TAG, "FileShare: General error during file download/extraction from server", e)
            _fileShareState.value = FileShareState.Error("Download/Extraction error: ${e.message}")

            // Send failure status
            webSocketService.sendUploadStatus(
                deviceId = deviceId,
                sessionId = sessionId,
                status = "error",
                message = "Error: ${e.message}",
                path = lastSuccessfulBrowsePathOnAndroid,
                fileName = name
            )
        } finally {
            if (tempZipFile.exists()) {
                tempZipFile.delete()
                Log.d(TAG, "FileShare: Deleted temporary downloaded ZIP: ${tempZipFile.absolutePath}")
            }
        }
    }


    private fun handleDownloadRequest(request: DownloadRequestMessage) {
        viewModelScope.launch {
            Log.i(TAG, "FileShare: Received download_request for paths: ${request.paths} in dir: $lastSuccessfulBrowsePathOnAndroid")

            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                Log.e(TAG, "FileShare: Token not found. Cannot handle download request.")
                return@launch
            }

            if (!hasManageExternalStoragePermission()) {
                Log.e(TAG, "FileShare: No All Files Access permission. Cannot provide files.")
                _fileShareUiEvents.postValue(FileShareUiEvent.PermissionOrDirectoryNeeded)
                return@launch
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Preparing ${request.paths.size} item(s) for download...",
                    Toast.LENGTH_SHORT
                ).show()
            }

            _fileShareState.value = FileShareState.PreparingDownloadFromAndroid(
                tokenForSession = request.sessionId,
                paths = request.paths // Original relative paths for state, if needed
            )

            withContext(Dispatchers.IO) {
                try {
                    val externalStorageRoot = Environment.getExternalStorageDirectory()
                    // Construct the base directory from which the files are being requested
                    val currentBrowsedDir = if (lastSuccessfulBrowsePathOnAndroid == "/" || lastSuccessfulBrowsePathOnAndroid.isEmpty()) {
                        externalStorageRoot
                    } else {
                        File(externalStorageRoot, lastSuccessfulBrowsePathOnAndroid.trimStart('/'))
                    }

                    if (!currentBrowsedDir.exists() || !currentBrowsedDir.isDirectory) {
                        throw IOException("Browsed directory does not exist or is not a directory: ${currentBrowsedDir.absolutePath}")
                    }

                    val downloadUrl = if (request.paths.size == 1  && request.paths[0].type != "folder") {
                        val requestedItemName = request.paths.first()
                        // Construct the full path to the single file
                        val singleFile = File(currentBrowsedDir, requestedItemName.name)

                        Log.d(TAG, "FileShare: Attempting to download single file: ${singleFile.absolutePath}")

                        if (singleFile.exists() && singleFile.isFile) {
                            uploadSingleFile(singleFile, request.sessionId, request.deviceId)
                        } else {
                            throw IOException("Requested file does not exist or is not a regular file: ${singleFile.absolutePath}")
                        }
                    } else {
                        // Multiple files: create and upload ZIP
                        // Construct absolute paths for all items to be zipped
                        val absolutePathsToZip = request.paths.map { itemName ->
                            File(currentBrowsedDir, itemName.name).absolutePath
                        }
                        Log.d(TAG, "FileShare: Attempting to zip multiple items: $absolutePathsToZip")
                        val zipFile = createZipFromPaths(absolutePathsToZip) // Pass absolute paths
                        if (zipFile != null && zipFile.exists() && zipFile.length() > 0) {
                            uploadZipToServer(zipFile, request.sessionId)
                        } else {
                            throw IOException("Failed to create ZIP file or ZIP file is empty from paths: $absolutePathsToZip")
                        }
                    }

                    if (downloadUrl != null) {
                        webSocketService.sendDownloadResponse(
                            deviceId = deviceId,
                            sessionId = request.sessionId,
                            downloadUrl = downloadUrl
                        )

                        _fileShareState.value = FileShareState.ReadyForDownloadFromAndroid(
                            tokenForSession = request.sessionId,
                            androidHostedUrl = downloadUrl
                        )

                        Log.i(TAG, "FileShare: Files successfully prepared for download at $downloadUrl")

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Files ready for download",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        throw IOException("Upload returned null download URL")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "FileShare: Error preparing files for download: ${e.message}", e)
                    _fileShareState.value = FileShareState.Error(
                        message = "Failed to prepare files for download: ${e.message}",
                        tokenForSession = request.sessionId
                    )

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Error preparing files: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }





    // For uploading single files
    @Throws(IOException::class)
    private suspend fun uploadSingleFile(
        file: File,
        sessionId: String, // Already have this
         deviceId : String, // Already have this
        // New parameter for contextual path
    ): String? = withContext(Dispatchers.IO) {
        try {
            val fileExtension = file.extension.let { if (it.isEmpty()) "" else ".$it" }
            val generatedFileName = "android_file_${deviceId}_${System.currentTimeMillis()}$fileExtension"

            val serverUploadEndpoint = "https://remote-control-gateway-production.up.railway.app/api/download"
            val originalFileName = file.name
            Log.i(TAG, "FileShare: Attempting to POST file to server endpoint: $serverUploadEndpoint with name: $generatedFileName, deviceId: $deviceId")


            val mimeType = determineMimeType(file.name) // You have a determineMimeType, use it or the previous logic

            val fileRequestBody = okhttp3.RequestBody.create(
                mimeType.toMediaTypeOrNull(),
                file
            )

            val requestBodyMultipart = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("deviceId", deviceId)
                .addFormDataPart("sessionId", sessionId)
                .addFormDataPart("file", originalFileName, fileRequestBody) // file.name or generatedFileName for Content-Disposition
                .build()

            val request = okhttp3.Request.Builder()
                .url(serverUploadEndpoint)
                .post(requestBodyMultipart)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "FileShare: File uploaded successfully to $serverUploadEndpoint. Download URL: $serverUploadEndpoint ")
                    return@withContext serverUploadEndpoint
                } else {
                    Log.e(TAG, "FileShare: Upload to $serverUploadEndpoint failed: ${response.code} ${response.message}")
                    Log.e(TAG, "Response body: ${response.body?.string()}")
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "FileShare: Error uploading file to server: ${e.message}", e)
            return@withContext null
        }
    }

    // New method to upload to an exact URL without returning a URL
    @Throws(IOException::class)
    private suspend fun uploadZipToExactUrl(zipFile: File, uploadUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "FileShare: Uploading ZIP to server: $uploadUrl")

            // Create the request body with the ZIP content
            val requestBody = okhttp3.RequestBody.create(
                "application/zip".toMediaTypeOrNull(),
                zipFile
            )

            // Create the multipart request
            val requestBodyMultipart = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", zipFile.name, requestBody)
                .build()

            // Create the request
            val request = okhttp3.Request.Builder()
                .url(uploadUrl)
                .post(requestBodyMultipart)
                .build()

            // Execute the request
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "FileShare: ZIP file uploaded successfully")
                    return@withContext true
                } else {
                    Log.e(TAG, "FileShare: Upload failed: ${response.code} ${response.message}")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "FileShare: Error uploading ZIP to server: ${e.message}", e)
            return@withContext false
        } finally {
            // Clean up the ZIP file
            if (zipFile.exists()) {
                zipFile.delete()
                Log.d(TAG, "FileShare: Deleted temporary ZIP file: ${zipFile.absolutePath}")
            }
        }
    }

    // 3. Create a ZIP file from the provided paths
    @Throws(IOException::class)
    private fun createZipFromPaths(absolutePaths: List<String>): File? { // Renamed parameter for clarity
        val zipFile = File(context.cacheDir, "android_files_${System.currentTimeMillis()}.zip")

        try {
            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                    for (absolutePath in absolutePaths) { // Iterate over absolute paths
                        val sourceFile = File(absolutePath) // Directly use the absolute path

                        if (!sourceFile.exists()) {
                            Log.w(TAG, "FileShare: Skipping non-existent path: $absolutePath")
                            continue
                        }

                        // pathInZip should be relative to the root of the zip file.
                        // Using sourceFile.name ensures files/folders are at the root of the zip.
                        // If sourceFile is a directory, its contents will be inside a folder named sourceFile.name.
                        val pathInZip = sourceFile.name

                        if (sourceFile.isFile) {
                            addFileToZip(zos, sourceFile, pathInZip)
                        } else if (sourceFile.isDirectory) {
                            addDirectoryToZip(zos, sourceFile, pathInZip)
                        }
                    }
                }
            }

            return if (zipFile.exists() && zipFile.length() > 0) zipFile else null
        } catch (e: Exception) {
            Log.e(TAG, "FileShare: Error creating ZIP file: ${e.message}", e)
            if (zipFile.exists()) {
                zipFile.delete()
            }
            throw e // Re-throw the exception to be caught by the caller
        }
    }

    // 4. Helper method to add a file to the ZIP
    @Throws(IOException::class)
    private fun addFileToZip(zos: ZipOutputStream, file: File, pathInZip: String) {
        Log.d(TAG, "FileShare: Adding file to ZIP: ${file.absolutePath} as $pathInZip")

        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "FileShare: Cannot add non-existent or non-file to ZIP: ${file.absolutePath}")
            return
        }

        val entry = java.util.zip.ZipEntry(pathInZip)
        entry.time = file.lastModified()
        zos.putNextEntry(entry)

        BufferedInputStream(FileInputStream(file)).use { bis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var count: Int
            while (bis.read(buffer).also { count = it } != -1) {
                zos.write(buffer, 0, count)
            }
        }

        zos.closeEntry()
    }

    // 5. Helper method to add a directory and its contents to the ZIP
    @Throws(IOException::class)
    private fun addDirectoryToZip(zos: ZipOutputStream, directory: File, pathInZip: String) {
        Log.d(TAG, "FileShare: Adding directory to ZIP: ${directory.absolutePath} as $pathInZip")

        if (!directory.exists() || !directory.isDirectory) {
            Log.w(TAG, "FileShare: Cannot add non-existent or non-directory to ZIP: ${directory.absolutePath}")
            return
        }

        // Add directory entry
        val dirEntry = java.util.zip.ZipEntry("$pathInZip/")
        dirEntry.time = directory.lastModified()
        zos.putNextEntry(dirEntry)
        zos.closeEntry()

        // Process all files and subdirectories
        directory.listFiles()?.forEach { file ->
            val entryPath = "$pathInZip/${file.name}"
            if (file.isDirectory) {
                addDirectoryToZip(zos, file, entryPath)
            } else {
                addFileToZip(zos, file, entryPath)
            }
        }
    }

    // 6. Upload the ZIP to the server
    @Throws(IOException::class)
    private suspend fun uploadZipToServer(zipFile: File, sessionId: String): String? = withContext(Dispatchers.IO) {
        try {
            // Generate a unique name for the ZIP file
            val zipName = "android_files_${deviceId}_${System.currentTimeMillis()}.zip"
            val uploadUrl = "https://remote-control-gateway-production.up.railway.app/api/download"

            Log.i(TAG, "FileShare: Uploading ZIP to server: $uploadUrl")

            // Create the request body with the ZIP content
            val requestBody = okhttp3.RequestBody.create(
                "application/zip".toMediaTypeOrNull(),
                zipFile
            )

            // Create the multipart request
            val requestBodyMultipart = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("deviceId", deviceId)
                .addFormDataPart("sessionId", sessionId)
                .addFormDataPart("file",  zipName, requestBody) // file.name or generatedFileName for Content-Disposition
                .build()

            // Create the request
            val request = okhttp3.Request.Builder()
                .url(uploadUrl)
                .post(requestBodyMultipart)
                .build()

            // Execute the request
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "FileShare: ZIP file uploaded successfully")
                    return@withContext uploadUrl
                } else {
                    Log.e(TAG, "FileShare: Upload failed: ${response.code} ${response.message}")
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "FileShare: Error uploading ZIP to server: ${e.message}", e)
            return@withContext null
        } finally {
            // Clean up the ZIP file
            if (zipFile.exists()) {
                zipFile.delete()
                Log.d(TAG, "FileShare: Deleted temporary ZIP file: ${zipFile.absolutePath}")
            }
        }
    }

    private suspend fun listDirectoryContents(relativePath: String): List<FileEntry>? = withContext(Dispatchers.IO) {
        Log.d(TAG, "FileShare: Attempting to list directory: '$relativePath'")

        if (hasManageExternalStoragePermission()) {
            Log.i(TAG, "FileShare: Using MANAGE_EXTERNAL_STORAGE for direct file access.")
            try {
                val externalStorageRoot = Environment.getExternalStorageDirectory()
                val targetPath = if (relativePath == "/" || relativePath.isEmpty()) {
                    externalStorageRoot
                } else {
                    File(externalStorageRoot, relativePath.trimStart('/'))
                }

                if (!targetPath.exists() || !targetPath.isDirectory) {
                    Log.w(TAG, "FileShare: Direct access path does not exist or not a directory: ${targetPath.absolutePath}")
                    return@withContext emptyList()
                }

                val entries = mutableListOf<FileEntry>()
                targetPath.listFiles()?.forEach { file ->
                    entries.add(
                        FileEntry(
                            name = file.name,
                            type = if (file.isDirectory) "folder" else "file",
                            size = if (file.isFile) file.length() else null
                        )
                    )
                }

                // Update last successful browse path
                lastSuccessfulBrowsePathOnAndroid = relativePath

                Log.d(TAG, "FileShare: Direct access found ${entries.size} entries in ${targetPath.absolutePath}")
                return@withContext entries
            } catch (e: Exception) {
                Log.e(TAG, "FileShare: Error using direct file access for '$relativePath'", e)
                return@withContext null
            }
        }

        Log.w(TAG, "FileShare: No All Files Access permission available for '$relativePath'.")
        return@withContext null
    }

   @Throws(IOException::class)
    private fun extractZip(zipFile: File, targetDirectory: File) {
        if (!targetDirectory.exists()) targetDirectory.mkdirs()
        Log.i(TAG, "FileShare: extractZip started. Target: ${targetDirectory.absolutePath}, ZIP: ${zipFile.name}")

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                val entryName = zipEntry.name

                // Strip the first path component (the top-level folder)
                val parts = entryName.split('/').filter { it.isNotEmpty() }
                val finalEntryName = if (parts.size > 1) {
                    parts.drop(1).joinToString("/")
                } else if (!entryName.endsWith("/")) {
                    parts.lastOrNull() ?: entryName // for file directly under root
                } else {
                    "" // it's the root folder itself
                }

                if (finalEntryName.isEmpty()) {
                    zis.closeEntry()
                    zipEntry = zis.nextEntry
                    continue
                }

                val targetFile = File(targetDirectory, finalEntryName)
                Log.d(TAG, "FileShare: Extracting '$entryName' → '$finalEntryName' to '${targetFile.absolutePath}'")

                if (zipEntry.isDirectory || entryName.endsWith('/')) {
                    if (!targetFile.exists()) {
                        if (!targetFile.mkdirs() && !targetFile.isDirectory) {
                            Log.w(TAG, "FileShare: Failed to create directory: '${targetFile.path}'")
                        }
                    }
                } else {
                    targetFile.parentFile?.let {
                        if (!it.exists() && !it.mkdirs() && !it.isDirectory) {
                            Log.w(TAG, "FileShare: Failed to create parent directory: '${it.path}'")
                        }
                    }

                    try {
                        FileOutputStream(targetFile).use { fos ->
                            BufferedOutputStream(fos).use { bos ->
                                zis.copyTo(bos)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "FileShare: Error writing file '${targetFile.path}'", e)
                    }
                }

                zis.closeEntry()
                zipEntry = zis.nextEntry
            }
        }

        Log.i(TAG, "FileShare: ZIP extraction complete to ${targetDirectory.absolutePath}")
    }



    // Helper to determine MIME type, can be expanded
    private fun determineMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "txt" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream" // Default binary type
        }
    }

    fun terminateFileShareSession(reason: String = "User terminated") {
        Log.i(TAG, "FileShare: Terminating session. Reason: $reason")
        currentFileShareToken = null
        _fileShareState.value = FileShareState.Terminated
    }

    override fun onCleared() {
        super.onCleared()
        stopObservingMessages()
        timeoutJob?.cancel()
        terminateFileShareSession("ViewModel cleared")
        webSocketService.disconnect()
        webRTCManager.release()
    }

    companion object {
        private const val BUFFER_SIZE = 4096
    }
}

// SessionState and FileShareState remain the same
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

sealed class FileShareState {
    object Idle : FileShareState()
    object Requesting : FileShareState()
    data class SessionDecisionPending(val tokenForSession: String) : FileShareState()
    data class UploadingToAndroid(
        val tokenForSession: String,
        val downloadUrl: String,
        val targetPathOnAndroid: String
    ) : FileShareState()
    data class Active(val tokenForSession: String) : FileShareState()
    data class Browsing(val tokenForSession: String, val path: String) : FileShareState()
    data class PreparingDownloadFromAndroid(val tokenForSession: String, val paths: List<PathItem>) : FileShareState()
    data class ReadyForDownloadFromAndroid(val tokenForSession: String, val androidHostedUrl: String) : FileShareState()
    data class Error(val message: String, val tokenForSession: String? = null) : FileShareState()
    object Terminated : FileShareState()
}

// File share UI events - simplified for the new approach
sealed class FileShareUiEvent {
    object RequestDirectoryPicker : FileShareUiEvent() // Kept for backward compatibility, will request All Files Access
    data class DirectorySelected(val uri: Uri) : FileShareUiEvent() // Signal to start screen capture
    object PermissionOrDirectoryNeeded : FileShareUiEvent() // Prompt for All Files Access
}