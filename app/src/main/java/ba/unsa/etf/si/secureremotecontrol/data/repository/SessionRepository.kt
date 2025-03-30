package ba.unsa.etf.si.secureremotecontrol.data.repository

import ba.unsa.etf.si.secureremotecontrol.data.models.Session
import ba.unsa.etf.si.secureremotecontrol.data.models.SessionStatus
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    suspend fun createSession(deviceId: String): Result<Session>
    suspend fun updateSessionStatus(sessionId: String, status: SessionStatus): Result<Session>
    suspend fun endSession(sessionId: String): Result<Session>
    fun observeSession(sessionId: String): Flow<Session>
    suspend fun getActiveSessions(): Result<List<Session>>
} 