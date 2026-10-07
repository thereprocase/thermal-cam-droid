package com.thereprocase.thermalfield

enum class NucPhase { NONE, REQUESTED, COMPLETED, FAILED }

data class NucFeedback(
    val phase: NucPhase = NucPhase.NONE,
    val updatedAtMillis: Long = 0,
    val error: String = "",
)

internal fun nucNotice(state: CameraUiState, nowMillis: Long): String? {
    if (!state.connected || state.fixture || state.archive) return null
    val event = state.nuc
    if (event.phase == NucPhase.NONE || event.phase == NucPhase.FAILED) return null
    if (state.busy && event.phase != NucPhase.REQUESTED) return null
    // This is a short command-context window, not a shutter detector. A static
    // scene can repeat samples, and a delivery pause can have other causes.
    if (event.phase == NucPhase.COMPLETED && nowMillis-event.updatedAtMillis > 2000) return null
    val command = if (event.phase == NucPhase.REQUESTED) "NUC command pending" else "NUC command completed"
    return when {
        state.frame.ageMs > 300 -> "$command · delivery paused · last image"
        state.frame.unchangedMs > 300 -> "$command · radiometric data unchanged"
        else -> command
    }
}

internal fun nucHistory(event: NucFeedback, nowMillis: Long): String = when (event.phase) {
    NucPhase.NONE -> "No manual NUC command in this session"
    NucPhase.REQUESTED -> "NUC command pending"
    NucPhase.COMPLETED -> "Last NUC command completed ${maxOf(0, nowMillis-event.updatedAtMillis)/1000} s ago · shutter motion not verified"
    NucPhase.FAILED -> "Last NUC command failed: ${event.error}"
}
