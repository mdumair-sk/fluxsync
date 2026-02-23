package com.fluxsync.core.transfer

import java.util.UUID

actual fun generateTransferHistoryId(): String = UUID.randomUUID().toString()
