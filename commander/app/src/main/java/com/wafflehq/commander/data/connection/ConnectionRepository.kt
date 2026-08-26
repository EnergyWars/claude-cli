package com.wafflehq.commander.data.connection

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wafflehq.commander.data.crypto.KeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.connectionDataStore by preferencesDataStore(name = "connection")

data class Connection(
    val host: String,
    val port: Int,
)

data class AuthSession(
    val token: String,
    val expiresAt: Instant,
)

data class Session(
    val connection: Connection,
    val auth: AuthSession?,
)

/** Read-only view used by [com.wafflehq.commander.data.api.ClServerApi] - lets tests fake the current session without an Android Context. */
interface ConnectionSource {
    val session: Flow<Session?>
}

/** Write-only view for [com.wafflehq.commander.data.api.ClServerApi] to store/drop a session without needing the full repository. */
interface SessionWriter {
    suspend fun saveAuthSession(token: String, expiresAt: Instant)
    suspend fun clearAuthSession()
}

@Singleton
class ConnectionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cipher: KeystoreCipher,
) : ConnectionSource, SessionWriter {
    private val hostKey = stringPreferencesKey("host")
    private val portKey = intPreferencesKey("port")
    private val tokenEncryptedKey = stringPreferencesKey("token_encrypted")
    private val tokenExpiresAtEpochSecondsKey = longPreferencesKey("token_expires_at_epoch_seconds")

    override val session: Flow<Session?> = context.connectionDataStore.data.map { prefs ->
        val host = prefs[hostKey]
        val port = prefs[portKey]
        if (host == null || port == null) {
            null
        } else {
            val encryptedToken = prefs[tokenEncryptedKey]
            val expiresAtEpochSeconds = prefs[tokenExpiresAtEpochSecondsKey]
            val auth = if (encryptedToken == null || expiresAtEpochSeconds == null) {
                null
            } else {
                AuthSession(cipher.decrypt(encryptedToken), Instant.ofEpochSecond(expiresAtEpochSeconds))
            }
            Session(Connection(host = host, port = port), auth)
        }
    }

    suspend fun saveConnection(host: String, port: Int) {
        context.connectionDataStore.edit { prefs ->
            prefs[hostKey] = host
            prefs[portKey] = port
            prefs.remove(tokenEncryptedKey)
            prefs.remove(tokenExpiresAtEpochSecondsKey)
        }
    }

    override suspend fun saveAuthSession(token: String, expiresAt: Instant) {
        context.connectionDataStore.edit { prefs ->
            prefs[tokenEncryptedKey] = cipher.encrypt(token)
            prefs[tokenExpiresAtEpochSecondsKey] = expiresAt.epochSecond
        }
    }

    override suspend fun clearAuthSession() {
        context.connectionDataStore.edit { prefs ->
            prefs.remove(tokenEncryptedKey)
            prefs.remove(tokenExpiresAtEpochSecondsKey)
        }
    }

    suspend fun clear() {
        context.connectionDataStore.edit { prefs -> prefs.clear() }
    }
}
