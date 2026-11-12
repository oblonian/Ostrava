package com.ostrava.app.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** Somewhere the app was asked to open from outside the UI (a notification, a tip). */
sealed interface NavRequest {
    data class OpenActivity(val id: Long) : NavRequest
    data class OpenDestination(val route: String) : NavRequest
}

/** One pending request at a time; the main screen consumes it and clears it. */
object NavRequests {
    val pending = MutableStateFlow<NavRequest?>(null)
}
