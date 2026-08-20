package com.wafflehq.commander.data.connection

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wafflehq.commander.data.crypto.KeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.connectionDataStore by preferencesDataStore(name = "connection")

data class Connection(
    val host: String,
    val port: Int,
    val totpSecret: String,
)

/** Read-only view used by [com.wafflehq.commander.data.api.ClServerApi] - lets tests fake the current connection without an Android Context. */
interface ConnectionSource {
    val connection: Flow<Connection?>
}

@Singleton
class ConnectionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cipher: KeystoreCipher,
) : ConnectionSource {
    private val hostKey = stringPreferencesKey("host")
    private val portKey = intPreferencesKey("port")
    private val encryptedSecretKey = stringPreferencesKey("totp_secret_encrypted")

    override val connection: Flow<Connection?> = context.connectionDataStore.data.map { prefs ->
        val host = prefs[hostKey]
        val port = prefs[portKey]
        val encryptedSecret = prefs[encryptedSecretKey]
        if (host == null || port == null || encryptedSecret == null) {
            null
        } else {
            Connection(host = host, port = port, totpSecret = cipher.decrypt(encryptedSecret))
        }
    }

    suspend fun save(connection: Connection) {
        context.connectionDataStore.edit { prefs ->
            prefs[hostKey] = connection.host
            prefs[portKey] = connection.port
            prefs[encryptedSecretKey] = cipher.encrypt(connection.totpSecret)
        }
    }

    suspend fun clear() {
        context.connectionDataStore.edit { prefs -> prefs.clear() }
    }
}
