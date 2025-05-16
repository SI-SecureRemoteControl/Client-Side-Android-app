
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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import ba.unsa.etf.si.secureremotecontrol.data.fileShare.*
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


data class UploadFilesMessage( // Make sure this matches the JSON structure from WebSocket
    @SerializedName("type") val type: String = "upload_files", // Should match the type in JSON
    @SerializedName("deviceId") val fromDeviceId: String, // ID of the device that initiated the upload (e.g., web client)
    @SerializedName("sessionId") val sessionId: String?,
    @SerializedName("downloadUrl") val downloadUrl: String, // URL for Android to download from
    @SerializedName("remotePath") val remotePath: String,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val webSocketService: WebSocketService,
    private val apiService: ApiService,
    @ApplicationContext private val context: Context,
    private val tokenDataStore: TokenDataStore,
    private val webRTCManager: WebRTCManager,
    private val okHttpClient: OkHttpClient // Assuming this is still needed for other parts
) : ViewModel() {

    private val TAG = "MainViewModel"

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState

    private var messageObservationJob: Job? = null
    private var timeoutJob: Job? = null

    // File Sharing State (remains for conceptual clarity, not directly tied to MANAGE_EXTERNAL_STORAGE)
    private val _fileShareState = MutableStateFlow<FileShareState>(FileShareState.Idle)
    val fileShareState: StateFlow<FileShareState> = _fileShareState
    private var currentFileShareToken: String? = null // For file sharing sessions, if applicable

    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    // File sharing UI events (still useful for SAF fallback or explicit SAF selection)
    private val _fileShareUiEvents = MutableSharedFlow<FileShareUiEvent>()
    val fileShareUiEvents: SharedFlow<FileShareUiEvent> = _fileShareUiEvents.asSharedFlow()

    // Root directory URI for SAF (used as a fallback or if user explicitly selects via picker)
    private var safRootDirectoryUri: Uri? = null
    private var lastSuccessfulBrowsePathOnAndroid: String = "/" // Default to root

    init {
        connectAndObserveMessages()

        // Load saved SAF root directory URI if available (for SAF fallback)
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("file_share_prefs", Context.MODE_PRIVATE)
            val savedUriStr = prefs.getString("saf_root_directory_uri", null) // Changed key for clarity
            if (savedUriStr != null) {
                try {
                    safRootDirectoryUri = Uri.parse(savedUriStr)
                    Log.d(TAG, "Loaded saved SAF root directory URI: $safRootDirectoryUri")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse saved SAF URI", e)
                }
            }
        }

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
                        "info" -> _sessionState.value = SessionState.Waiting
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
                        "offer", "ice-candidate" -> handleWebRtcSignaling(response) // Combined handler
                        "keyboard" -> {
                            val payload = response.optJSONObject("payload") ?: return@collect
                            val key = payload.getString("key")
                            if (payload.getString("type") == "keydown") {
                                RemoteControlAccessibilityService.instance?.inputCharacter(key)
                            }
                        }
                        "browse_request" -> { // Already handled by dedicated observer, but good to have a case
                            Log.d(TAG, "Browse request received (also handled by dedicated observer).")
                            // val browseRequestMessage = Gson().fromJson(message, BrowseRequestMessage::class.java)
                            // handleBrowseRequest(browseRequestMessage)
                        }

                        "upload_files" -> {
                            Log.d(TAG, "Received 'upload_files' message type.")
                            // Assuming the structure of UploadFilesMessage matches the top-level fields of jsonObject
                            // If "deviceId", "downloadUrl", etc. are nested under a "payload", adjust parsing.
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
        viewModelScope.launch { webRTCManager.startObservingRtcMessages(lifecycleOwner) }
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

    // Request the user to select a directory (for SAF fallback)
    fun requestDirectoryAccessViaPicker() {
        viewModelScope.launch {
            _fileShareUiEvents.emit(FileShareUiEvent.RequestDirectoryPicker)
        }
    }

    // Set the selected SAF directory URI (after picker)
    fun setSafRootDirectoryUri(uri: Uri) {
        safRootDirectoryUri = uri
        val prefs = context.getSharedPreferences("file_share_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("saf_root_directory_uri", uri.toString()).apply()
        Log.i(TAG, "FileShare: SAF Root Directory URI set to: $uri")
        viewModelScope.launch {
            _fileShareUiEvents.emit(FileShareUiEvent.DirectorySelected(uri)) // Notify UI
        }
    }

    private fun handleBrowseRequest(message: BrowseRequestMessage) {
        viewModelScope.launch {
            val token = tokenDataStore.token.firstOrNull()
            if (token.isNullOrEmpty()) {
                Log.e(TAG, "FileShare: Token not found. Cannot browse.")
                return@launch
            }
            Log.i(TAG, "FileShare: Received browse_request for path: ${message.path} in session (token ${token})")

            try {
                val entries = listDirectoryContents(message.path)
                if (entries == null && !hasManageExternalStoragePermission() && safRootDirectoryUri == null) {
                    // This means neither MANAGE_EXTERNAL_STORAGE is granted, nor a SAF URI is set.
                    // We need to ask the user to grant MANAGE_EXTERNAL_STORAGE or pick a dir via SAF.
                    Log.w(TAG, "FileShare: No permission for file access. MANAGE_EXTERNAL_STORAGE not granted and no SAF URI selected.")
                    // Emitting an event to UI to handle this situation (e.g., show a message or prompt for MANAGE_EXTERNAL_STORAGE)
                    // For now, we'll send an empty response. The UI should ideally prompt the user.
                    _fileShareUiEvents.emit(FileShareUiEvent.PermissionOrDirectoryNeeded) // New event
                    webSocketService.sendBrowseResponse(deviceId, token, message.path, emptyList())
                    return@launch
                }

                webSocketService.sendBrowseResponse(deviceId, token, message.path, entries ?: emptyList())
                Log.d(TAG, "FileShare: Browse response sent for path ${message.path} with ${entries?.size ?: 0} entries.")
            } catch (e: Exception) {
                Log.e(TAG, "FileShare: Error browsing path ${message.path}", e)
                webSocketService.sendBrowseResponse(deviceId, token, message.path, emptyList())
            }
        }
    }

    private suspend fun handleUploadFilesToAndroid(message: UploadFilesMessage) = withContext(Dispatchers.IO) {
        Log.i(TAG, "FileShare: DOWNLOAD_TO_ANDROID started. URL: ${message.downloadUrl}, Web's remotePath: '${message.remotePath}', Last browsed Android base: '$lastSuccessfulBrowsePathOnAndroid'")

        _fileShareState.value = FileShareState.UploadingToAndroid( // State indicates Android is receiving files
            tokenForSession = message.sessionId ?: currentFileShareToken ?: "unknown",
            downloadUrl = message.downloadUrl,
            targetPathOnAndroid = lastSuccessfulBrowsePathOnAndroid // Base path on Android
        )

        val tempZipFile = File(context.cacheDir, "server_download_${System.currentTimeMillis()}.zip")

        try {
            // 1. Download the ZIP file from the server
            Log.d(TAG, "FileShare: Downloading from ${message.downloadUrl} to ${tempZipFile.absolutePath}")
            val request = Request.Builder().url(message.downloadUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "FileShare: Download from server failed. Code: ${response.code}, Message: ${response.message}")
                    _fileShareState.value = FileShareState.Error("Download failed: ${response.code} ${response.message}")
                    return@withContext
                }
                response.body?.byteStream()?.use { inputStream ->
                    FileOutputStream(tempZipFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: run {
                    Log.e(TAG, "FileShare: Download from server failed. Response body is null.")
                    _fileShareState.value = FileShareState.Error("Download failed: Empty response from server")
                    return@withContext
                }
            }
            Log.d(TAG, "FileShare: Download from server complete. Size: ${tempZipFile.length()} bytes")

            // 2. Determine final target directory on Android and extract
            if (hasManageExternalStoragePermission()) {
                val androidStorageRoot = Environment.getExternalStorageDirectory()
                // Normalize last browsed path
                val baseAndroidPath = if (lastSuccessfulBrowsePathOnAndroid == "/") "" else lastSuccessfulBrowsePathOnAndroid.trim('/')
                // Normalize web's remote path hint
                val webSubPath = message.remotePath.trim('/')
                // Combine them
                val fullRelativePath = if (baseAndroidPath.isEmpty()) webSubPath else if (webSubPath.isEmpty()) baseAndroidPath else "$baseAndroidPath/$webSubPath"

                val finalTargetDir = File(androidStorageRoot, fullRelativePath.trimStart('/'))

                if (!finalTargetDir.exists() && !finalTargetDir.mkdirs()) {
                    Log.e(TAG, "FileShare: Could not create target directory (direct): ${finalTargetDir.absolutePath}")
                    _fileShareState.value = FileShareState.Error("Failed to create target directory on Android")
                    return@withContext
                }
                Log.i(TAG, "FileShare: Extracting downloaded ZIP (direct access) to: ${finalTargetDir.absolutePath}")
                extractZip(tempZipFile, finalTargetDir)
                _fileShareState.value = FileShareState.Active(message.sessionId ?: "unknown") // Or a DownloadComplete state
                Log.i(TAG, "FileShare: Files downloaded and extracted successfully to (direct): ${finalTargetDir.absolutePath}")
                withContext(Dispatchers.Main) { Toast.makeText(context, "Files received into: ${fullRelativePath.ifEmpty { "root" }}", Toast.LENGTH_LONG).show() }

            } else if (safRootDirectoryUri != null) {
                var currentSafTargetDir = DocumentFile.fromTreeUri(context, safRootDirectoryUri!!)
                if (currentSafTargetDir == null || !currentSafTargetDir.isDirectory) {
                    Log.e(TAG, "FileShare: SAF root directory not accessible: $safRootDirectoryUri")
                    _fileShareState.value = FileShareState.Error("SAF root not accessible")
                    return@withContext
                }

                // Navigate to lastSuccessfulBrowsePathOnAndroid within SAF root
                val browsePathSegments = lastSuccessfulBrowsePathOnAndroid.trim('/').split('/').filter { it.isNotEmpty() }
                for (segment in browsePathSegments) {
                    currentSafTargetDir = currentSafTargetDir?.findFile(segment)?.also {
                        if (!it.isDirectory) {
                            Log.e(TAG, "FileShare: SAF segment for browse path '$segment' is a file, not directory.")
                            throw IOException("SAF browse path conflict: '$segment' is a file.")
                        }
                    } ?: throw IOException("SAF browse path segment '$segment' not found.")
                }
                // currentSafTargetDir now points to the Android base path (e.g., /MyFolder/Pictures)

                // Create subdirectories based on message.remotePath
                val webRemotePathSegments = message.remotePath.trim('/').split('/').filter { it.isNotEmpty() }
                for (segment in webRemotePathSegments) {
                    val existingSubDir = currentSafTargetDir?.findFile(segment)
                    currentSafTargetDir = if (existingSubDir != null) {
                        if (!existingSubDir.isDirectory) {
                            Log.e(TAG, "FileShare: SAF segment for remotePath '$segment' is a file, not directory.")
                            throw IOException("SAF remotePath conflict: '$segment' is a file.")
                        }
                        existingSubDir
                    } else {
                        currentSafTargetDir?.createDirectory(segment)
                            ?: throw IOException("Could not create SAF subdirectory '$segment'.")
                    }
                }
                // currentSafTargetDir now points to the final destination (e.g., /MyFolder/Pictures/Vacation Shots)

                Log.i(TAG, "FileShare: Extracting downloaded ZIP (SAF) to: ${currentSafTargetDir?.uri}")
                if (currentSafTargetDir != null) {
                    extractZipToSaf(tempZipFile, currentSafTargetDir)
                }
                _fileShareState.value = FileShareState.Active(message.sessionId ?: "unknown")
                Log.i(TAG, "FileShare: Files downloaded and extracted successfully to (SAF): ${currentSafTargetDir?.uri}")
                withContext(Dispatchers.Main) { Toast.makeText(context, "Files received (SAF) into path ending with: ${message.remotePath.ifEmpty{"root"}}", Toast.LENGTH_LONG).show() }

            } else {
                Log.e(TAG, "FileShare: No write access. Grant MANAGE_EXTERNAL_STORAGE or pick SAF directory.")
                _fileShareState.value = FileShareState.Error("No permission on Android to save downloaded files.")
                _fileShareUiEvents.emit(FileShareUiEvent.PermissionOrDirectoryNeeded)
                return@withContext
            }

        } catch (e: IOException) { // Catch specific IOExceptions from SAF navigation/creation
            Log.e(TAG, "FileShare: IOException during file download/extraction from server", e)
            _fileShareState.value = FileShareState.Error("IO Error: ${e.message}")
        }
        catch (e: Exception) {
            Log.e(TAG, "FileShare: General error during file download/extraction from server", e)
            _fileShareState.value = FileShareState.Error("Download/Extraction error: ${e.message}")
        } finally {
            if (tempZipFile.exists()) {
                tempZipFile.delete()
                Log.d(TAG, "FileShare: Deleted temporary downloaded ZIP: ${tempZipFile.absolutePath}")
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
                    return@withContext emptyList() // Path doesn't exist or isn't a dir
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
                Log.d(TAG, "FileShare: Direct access found ${entries.size} entries in ${targetPath.absolutePath}")
                return@withContext entries
            } catch (e: Exception) {
                Log.e(TAG, "FileShare: Error using direct file access for '$relativePath'", e)
                // Fall through to SAF or return null if SAF also fails
            }
        }

        // Fallback to SAF if MANAGE_EXTERNAL_STORAGE is not available or failed
        val currentSafRootUri = safRootDirectoryUri
        if (currentSafRootUri != null) {
            Log.i(TAG, "FileShare: MANAGE_EXTERNAL_STORAGE not available or failed. Trying SAF with root: $currentSafRootUri")
            try {
                var targetDocFile = DocumentFile.fromTreeUri(context, currentSafRootUri)
                if (targetDocFile == null || !targetDocFile.exists()) {
                    Log.e(TAG, "FileShare: SAF root directory not accessible: $currentSafRootUri")
                    return@withContext null // Cannot access SAF root
                }

                if (relativePath != "/" && relativePath.isNotEmpty()) {
                    val pathSegments = relativePath.trim('/').split('/')
                    for (segment in pathSegments) {
                        if (segment.isNotEmpty()) {
                            val childDoc = targetDocFile?.findFile(segment)
                            if (childDoc == null || !childDoc.exists()) {
                                Log.w(TAG, "FileShare: SAF path segment not found: '$segment' in '$relativePath'")
                                return@withContext emptyList() // Path segment not found
                            }
                            targetDocFile = childDoc
                        }
                    }
                }

                if (targetDocFile == null || !targetDocFile.isDirectory) {
                    Log.w(TAG, "FileShare: SAF target path is not a directory: '$relativePath'")
                    return@withContext emptyList() // Target not a directory
                }

                val entries = mutableListOf<FileEntry>()
                targetDocFile.listFiles().forEach { docFile ->
                    entries.add(
                        FileEntry(
                            name = docFile.name ?: "Unknown",
                            type = if (docFile.isDirectory) "folder" else "file",
                            size = if (docFile.isFile) docFile.length() else null
                        )
                    )
                }
                Log.d(TAG, "FileShare: SAF found ${entries.size} entries for '$relativePath'")
                return@withContext entries
            } catch (e: Exception) {
                Log.e(TAG, "FileShare: Error using SAF for '$relativePath'", e)
                return@withContext null // SAF attempt failed
            }
        }

        Log.w(TAG, "FileShare: No access method available for '$relativePath'. MANAGE_EXTERNAL_STORAGE not granted and no SAF root URI set/valid.")
        return@withContext null // Neither method worked
    }


    /*@Throws(IOException::class)
    private fun extractZip(zipFile: File, targetDirectory: File) {
        if (!targetDirectory.exists()) targetDirectory.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var zipEntry: java.util.zip.ZipEntry? = zis.nextEntry
            while (zipEntry != null) {
                val newPath = File(targetDirectory, zipEntry.name)
                if (zipEntry.isDirectory) {
                    if (!newPath.mkdirs() && !newPath.isDirectory) { // Check if already exists as dir
                        Log.w(TAG, "FileShare: Failed to create directory or already exists as file: ${newPath.path}")
                    }
                } else {
                    // Ensure parent directory exists
                    newPath.parentFile?.mkdirs()
                    FileOutputStream(newPath).use { fos ->
                        BufferedOutputStream(fos).use { bos ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (zis.read(buffer, 0, BUFFER_SIZE).also { read = it } != -1) {
                                bos.write(buffer, 0, read)
                            }
                        }
                    }
                }
                zis.closeEntry()
                zipEntry = zis.nextEntry
            }
        }
        Log.d(TAG, "FileShare: ZIP extraction complete to ${targetDirectory.absolutePath}")
    }*/

    // MainViewModel.kt

// ... (imports and other code as before)

    // MainViewModel.kt

// ... (imports and other code as before)

    @Throws(IOException::class)
    private fun extractZip(zipFile: File, targetDirectory: File) {
        if (!targetDirectory.exists()) targetDirectory.mkdirs()
        Log.i(TAG, "FileShare: extractZip started. Target: ${targetDirectory.absolutePath}, ZIP: ${zipFile.name}")

        val allEntryNames = mutableListOf<String>()

        // First pass: Collect all entry names
        FileInputStream(zipFile).use { fis ->
            ZipInputStream(BufferedInputStream(fis)).use { zisForCheck ->
                var entry: java.util.zip.ZipEntry? = zisForCheck.nextEntry
                while (entry != null) {
                    allEntryNames.add(entry.name)
                    entry = zisForCheck.nextEntry
                }
            }
        }

        if (allEntryNames.isEmpty()) {
            Log.w(TAG, "FileShare: ZIP file is empty.")
            return
        }

        var commonBasePathToStrip: String? = null

        // Determine the common base path IF all entries share one.
        // The common base path must be a directory (end with '/').
        if (allEntryNames.isNotEmpty()) {
            // Find the shortest entry name, as the common path cannot be longer than that.
            var potentialPrefix = allEntryNames.minByOrNull { it.length } ?: ""

            // Trim until it's a directory or empty
            while (potentialPrefix.isNotEmpty() && !potentialPrefix.endsWith("/")) {
                val lastSlash = potentialPrefix.lastIndexOf('/')
                if (lastSlash == -1) { // No slash found, not a directory path
                    potentialPrefix = ""
                    break
                }
                potentialPrefix = potentialPrefix.substring(0, lastSlash + 1)
            }

            if (potentialPrefix.isNotEmpty()) {
                var isCommonToAll = true
                for (name in allEntryNames) {
                    if (!name.startsWith(potentialPrefix)) {
                        isCommonToAll = false
                        break
                    }
                }
                if (isCommonToAll) {
                    // Check if this common prefix is essentially the *only* thing at the root level.
                    // This means that after stripping this prefix, no entry should *start* with another directory.
                    // Or, more simply, if this prefix truly represents a single root folder.
                    // A simple way to check: are there any entries that are NOT this prefix,
                    // and also NOT starting with this prefix + something else?
                    // If all entries start with "folder/", then "folder/" is the common base.
                    commonBasePathToStrip = potentialPrefix
                }
            }
        }

        Log.i(TAG, "FileShare: Determined common base path to strip: '$commonBasePathToStrip'")

        // Actual extraction
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var zipEntry: java.util.zip.ZipEntry? = zis.nextEntry
            while (zipEntry != null) {
                var currentEntryName = zipEntry.name
                Log.d(TAG, "FileShare: Processing ZIP entry: '$currentEntryName'")

                if (commonBasePathToStrip != null && currentEntryName.startsWith(commonBasePathToStrip)) {
                    currentEntryName = currentEntryName.substring(commonBasePathToStrip.length)
                    Log.d(TAG, "FileShare: Stripped common base. New entry name: '$currentEntryName'")
                }

                if (currentEntryName.isEmpty()) {
                    Log.d(TAG, "FileShare: Entry name is empty after stripping, skipping.")
                    zis.closeEntry()
                    zipEntry = zis.nextEntry
                    continue
                }

                val newFile = File(targetDirectory, currentEntryName)
                Log.d(TAG, "FileShare: Target file path on device: '${newFile.absolutePath}'")

                if (zipEntry.isDirectory || currentEntryName.endsWith("/")) {
                    if (!newFile.mkdirs() && !newFile.isDirectory) {
                        Log.w(TAG, "FileShare: Failed to create directory: '${newFile.path}'")
                    } else {
                        Log.d(TAG, "FileShare: Created/Ensured directory: '${newFile.path}'")
                    }
                } else {
                    Log.d(TAG, "FileShare: Extracting file to: '${newFile.path}'")
                    newFile.parentFile?.let {
                        if (!it.exists() && !it.mkdirs() && !it.isDirectory) {
                            Log.w(TAG, "FileShare: Failed to create parent directory: '${it.path}'")
                        } else {
                            Log.d(TAG, "FileShare: Ensured parent directory exists: '${it.path}'")
                        }
                    }
                    try {
                        FileOutputStream(newFile).use { fos ->
                            BufferedOutputStream(fos).use { bos ->
                                zis.copyTo(bos)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "FileShare: Error writing file '${newFile.path}'", e)
                    }
                }
                zis.closeEntry()
                zipEntry = zis.nextEntry
            }
        }
        Log.i(TAG, "FileShare: ZIP extraction complete to ${targetDirectory.absolutePath}")
    }


    @Throws(IOException::class)
    private fun extractZipToSaf(zipFile: File, targetSafDirectory: DocumentFile) {
        Log.i(TAG, "FileShare SAF: extractZipToSaf started. Target SAF URI: ${targetSafDirectory.uri}, ZIP: ${zipFile.name}")

        val allEntryNames = mutableListOf<String>()
        FileInputStream(zipFile).use { fis ->
            ZipInputStream(BufferedInputStream(fis)).use { zisForCheck ->
                var entry: java.util.zip.ZipEntry? = zisForCheck.nextEntry
                while (entry != null) {
                    allEntryNames.add(entry.name)
                    entry = zisForCheck.nextEntry
                }
            }
        }

        if (allEntryNames.isEmpty()) {
            Log.w(TAG, "FileShare SAF: ZIP file is empty.")
            return
        }

        var commonBasePathToStrip: String? = null
        if (allEntryNames.isNotEmpty()) {
            var potentialPrefix = allEntryNames.minByOrNull { it.length } ?: ""
            while (potentialPrefix.isNotEmpty() && !potentialPrefix.endsWith("/")) {
                val lastSlash = potentialPrefix.lastIndexOf('/')
                if (lastSlash == -1) { potentialPrefix = ""; break }
                potentialPrefix = potentialPrefix.substring(0, lastSlash + 1)
            }
            if (potentialPrefix.isNotEmpty()) {
                if (allEntryNames.all { it.startsWith(potentialPrefix) }) {
                    commonBasePathToStrip = potentialPrefix
                }
            }
        }
        Log.i(TAG, "FileShare SAF: Determined common base path to strip: '$commonBasePathToStrip'")


        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var zipEntry: java.util.zip.ZipEntry? = zis.nextEntry
            while (zipEntry != null) {
                var currentEntryName = zipEntry.name
                Log.d(TAG, "FileShare SAF: Processing ZIP entry: '$currentEntryName'")

                if (commonBasePathToStrip != null && currentEntryName.startsWith(commonBasePathToStrip)) {
                    currentEntryName = currentEntryName.substring(commonBasePathToStrip.length)
                    Log.d(TAG, "FileShare SAF: Stripped common base. New entry name: '$currentEntryName'")
                }

                if (currentEntryName.isEmpty()) {
                    Log.d(TAG, "FileShare SAF: Entry name is empty after stripping, skipping.")
                    zis.closeEntry()
                    zipEntry = zis.nextEntry
                    continue
                }

                if (zipEntry.isDirectory || currentEntryName.endsWith("/")) {
                    var currentDirDoc = targetSafDirectory
                    currentEntryName.trim('/').split('/').filter{it.isNotEmpty()}.forEach { segment -> // filter empty after split
                        val existingDir = currentDirDoc.findFile(segment)
                        currentDirDoc = if (existingDir != null) {
                            if (!existingDir.isDirectory) throw IOException("SAF Path conflict: '$segment' is a file, expected directory.")
                            existingDir
                        } else {
                            currentDirDoc.createDirectory(segment)
                                ?: throw IOException("Could not create SAF directory: '$segment' in ${currentDirDoc.uri}")
                        }
                    }
                } else {
                    var parentDirDoc = targetSafDirectory
                    val pathParts = currentEntryName.split('/')
                    val fileName = pathParts.last()
                    val dirPathParts = pathParts.dropLast(1)

                    dirPathParts.filter{it.isNotEmpty()}.forEach { segment ->
                        val existingDir = parentDirDoc.findFile(segment)
                        parentDirDoc = if (existingDir != null) {
                            if (!existingDir.isDirectory) throw IOException("SAF Path conflict: '$segment' is a file, expected parent directory.")
                            existingDir
                        } else {
                            parentDirDoc.createDirectory(segment)
                                ?: throw IOException("Could not create SAF parent directory: '$segment' for $fileName in ${parentDirDoc.uri}")
                        }
                    }
                    val newFileDoc = parentDirDoc.createFile(determineMimeType(fileName), fileName)
                        ?: throw IOException("Could not create SAF file: '$fileName' in ${parentDirDoc.uri}")

                    context.contentResolver.openOutputStream(newFileDoc.uri)?.use { os ->
                        BufferedOutputStream(os).use { bos -> zis.copyTo(bos) }
                    } ?: throw IOException("Could not open output stream for SAF file: ${newFileDoc.uri}")
                }
                zis.closeEntry()
                zipEntry = zis.nextEntry
            }
        }
        Log.i(TAG, "FileShare SAF: ZIP extraction complete to ${targetSafDirectory.uri}")
    }

// ... (rest of your MainViewModel code)




// ... (rest of MainViewModel, including companion object with BUFFER_SIZE and determineMimeType)

   /* @Throws(IOException::class)
    private fun extractZipToSaf(zipFile: File, targetSafDirectory: DocumentFile) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var zipEntry: java.util.zip.ZipEntry? = zis.nextEntry
            while (zipEntry != null) {
                val entryName = zipEntry.name
                if (zipEntry.isDirectory) {
                    // Create directory structure within SAF
                    var currentDir = targetSafDirectory
                    entryName.trim('/').split('/').forEach { segment ->
                        if (segment.isNotEmpty()) {
                            val existingDir = currentDir.findFile(segment)
                            currentDir = if (existingDir != null) {
                                if (!existingDir.isDirectory) throw IOException("Path conflict: '$segment' is a file, expected directory.")
                                existingDir
                            } else {
                                currentDir.createDirectory(segment)
                                    ?: throw IOException("Could not create SAF directory: $segment")
                            }
                        }
                    }
                } else {
                    // Create file within SAF structure
                    var parentDir = targetSafDirectory
                    val pathParts = entryName.split('/')
                    val fileName = pathParts.last()
                    val dirPathParts = pathParts.dropLast(1)

                    dirPathParts.forEach { segment ->
                        if (segment.isNotEmpty()) {
                            val existingDir = parentDir.findFile(segment)
                            parentDir = if (existingDir != null) {
                                if (!existingDir.isDirectory) throw IOException("Path conflict: '$segment' is a file, expected directory for file parent.")
                                existingDir
                            } else {
                                parentDir.createDirectory(segment)
                                    ?: throw IOException("Could not create SAF parent directory: $segment for $fileName")
                            }
                        }
                    }

                    val newFile = parentDir.createFile(
                        determineMimeType(fileName), // You might need a helper for MIME types
                        fileName
                    ) ?: throw IOException("Could not create SAF file: $fileName in ${parentDir.uri}")

                    context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
                        BufferedOutputStream(os).use { bos ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (zis.read(buffer, 0, BUFFER_SIZE).also { read = it } != -1) {
                                bos.write(buffer, 0, read)
                            }
                        }
                    } ?: throw IOException("Could not open output stream for SAF file: ${newFile.uri}")
                }
                zis.closeEntry()
                zipEntry = zis.nextEntry
            }
        }
        Log.d(TAG, "FileShare: ZIP extraction to SAF complete to ${targetSafDirectory.uri}")
    }*/


    // Helper to determine MIME type, can be expanded
    private fun determineMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "txt" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            // Add more common types or use MimeTypeMap
            else -> "application/octet-stream" // Default binary type
        }
    }


    fun terminateFileShareSession(reason: String = "User terminated") {
        Log.i(TAG, "FileShare: Terminating session. Reason: $reason")
        // Add any specific file share session cleanup logic here
        currentFileShareToken = null
        _fileShareState.value = FileShareState.Terminated
    }

    override fun onCleared() {
        super.onCleared()
        stopObservingMessages()
        timeoutJob?.cancel()
        terminateFileShareSession("ViewModel cleared")
        webSocketService.disconnect() // Ensure WebSocket is closed
        webRTCManager.release() // Release WebRTC resources
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
    object Requesting : FileShareState() // If you have a state for requesting a file share session
    data class SessionDecisionPending(val tokenForSession: String) : FileShareState() // If applicable

    // States relevant to the "upload_files" (download to Android) scenario
    data class UploadingToAndroid( // Renamed from the error, but this is what the code uses
        val tokenForSession: String,
        val downloadUrl: String,
        val targetPathOnAndroid: String
    ) : FileShareState()

    data class Active(val tokenForSession: String) : FileShareState() // General active file sharing state

    // States for browsing (if you want to track this specifically)
    data class Browsing(val tokenForSession: String, val path: String) : FileShareState()

    // States for when Android is preparing to send files (if you implement that flow)
    data class PreparingDownloadFromAndroid(val tokenForSession: String, val paths: List<String>) : FileShareState()
    data class ReadyForDownloadFromAndroid(val tokenForSession: String, val androidHostedUrl: String) : FileShareState()

    // General error state for file sharing operations
    data class Error(val message: String, val tokenForSession: String? = null) : FileShareState()

    object Terminated : FileShareState()
}

// File share UI events
sealed class FileShareUiEvent {
    object RequestDirectoryPicker : FileShareUiEvent() // For SAF fallback
    data class DirectorySelected(val uri: Uri) : FileShareUiEvent() // For SAF
    object PermissionOrDirectoryNeeded : FileShareUiEvent() // New: To prompt user if no access method
}