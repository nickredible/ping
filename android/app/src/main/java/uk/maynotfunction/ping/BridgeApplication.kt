package uk.maynotfunction.ping

import android.app.Application
import android.content.Context
import uk.maynotfunction.ping.data.BridgeRepository
import uk.maynotfunction.ping.data.ContactsRepository
import uk.maynotfunction.ping.data.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide singletons. Small enough that a DI framework would be overkill. */
class BridgeApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repository by lazy { BridgeRepository(this, appScope) }
    val relay by lazy { RelayClient(this, repository) }
    val contacts by lazy { ContactsRepository(this) }
}

val Context.bridgeApp: BridgeApplication get() = applicationContext as BridgeApplication
