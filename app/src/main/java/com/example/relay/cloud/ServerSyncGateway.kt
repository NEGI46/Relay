package com.example.relay.cloud

interface ServerSyncGateway { suspend fun sync(): ServerSyncResult }
sealed interface ServerSyncResult { data object NotImplemented : ServerSyncResult }

object NoOpServerSyncGateway : ServerSyncGateway {
    override suspend fun sync(): ServerSyncResult = ServerSyncResult.NotImplemented
}

