package com.sieve.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadStateMachineTest {
    @Test fun exit() {
        assertEquals(DownloadState.DONE, DownloadStateMachine.mapExit(0))
        assertEquals(DownloadState.ERROR, DownloadStateMachine.mapExit(2))
    }

    @Test fun persist() {
        assertEquals(DownloadState.QUEUED, DownloadStateMachine.mapPersistState(DownloadState.POSTPROCESS))
        assertEquals(DownloadState.QUEUED, DownloadStateMachine.mapPersistState(DownloadState.DOWNLOADING))
        assertEquals(DownloadState.DONE, DownloadStateMachine.mapPersistState(DownloadState.DONE))
        assertEquals(1f, DownloadStateMachine.mapPersistProgress(DownloadState.DONE))
        assertEquals(0f, DownloadStateMachine.mapPersistProgress(DownloadState.DOWNLOADING))
    }

    @Test fun restore() {
        assertEquals(DownloadState.QUEUED, DownloadStateMachine.mapRestoreState(DownloadState.PAUSED))
        assertEquals(DownloadState.DONE, DownloadStateMachine.mapRestoreState(DownloadState.DONE))
        assertEquals(DownloadState.ERROR, DownloadStateMachine.mapRestoreState(DownloadState.ERROR))
    }
}
