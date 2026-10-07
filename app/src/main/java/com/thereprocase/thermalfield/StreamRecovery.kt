package com.thereprocase.thermalfield

internal fun canRestartStalledSource(state: CameraUiState): Boolean =
    state.connected && !state.fixture && !state.archive && !state.busy && !state.profileApplying && !state.saving &&
        state.frame.frame > 0 && state.frame.error.isEmpty() && state.frame.ageMs >= 3000
