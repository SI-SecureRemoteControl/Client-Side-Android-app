package ba.unsa.etf.si.secureremotecontrol.data.fileShare // Or your preferred package

import com.google.gson.annotations.SerializedName

// Common fields, can be part of a base class if you prefer
// For simplicity, I'll repeat them for now.

// 🔄 Session Initialization & File Browsing
data class RequestSessionFileshareMessage(
    val type: String = "request_session_fileshare",
    val deviceId: String,
    val sessionId: String
)

data class DecisionFileshareMessage(
    val type: String, // "decision_fileshare"
    val deviceId: String,
    val sessionId: String,
    val decision: Boolean
)

data class BrowseRequestMessage(
    val type: String, // "browse_request"
    val deviceId: String,
    val sessionId: String,
    val path: String
)

data class FileEntry(
    val name: String,
    val type: String, // "file" or "folder"
    val size: Long? = null // Nullable for folders
)

data class BrowseResponseMessage(
    val type: String = "browse_response",
    val deviceId: String,
    val sessionId: String,
    val path: String,
    val entries: List<FileEntry>
)

// ⬆️ UPLOAD FILES
data class UploadFilesMessage( // Received by Android
    val type: String, // "upload_files"
    val deviceId: String,
    val sessionId: String,
    @SerializedName("downloadUrl") // Ensure GSON uses this key
    val downloadUrl: String
)

data class UploadStatusMessage( // Sent by Android
    val type: String = "upload_status",
    val deviceId: String,
    val sessionId: String,
    val status: String, // "success", "failure", "progress" (optional)
    val message: String? = null
)

// ⬇️ DOWNLOAD FILES
data class DownloadRequestMessage( // Received by Android
    val type: String, // "download_request"
    val deviceId: String,
    val sessionId: String,
    val paths: List<String>
)

data class DownloadResponseMessage( // Sent by Android
    val type: String = "download_response",
    val deviceId: String,
    val sessionId: String,
    @SerializedName("downloadUrl")
    val downloadUrl: String
)

// A generic base for easier parsing if you want to handle all messages in one flow
// and then distinguish by type.
// open class BaseMessage(val type: String, val deviceId: String, val sessionId: String)