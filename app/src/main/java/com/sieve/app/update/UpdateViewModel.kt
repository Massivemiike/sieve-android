package com.sieve.app.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sieve.app.BuildConfig
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class UpdateViewModel(private val repo: UpdateRepository) : ViewModel() {
    val state: StateFlow<UpdateUiState> = repo.state

    fun checkNow() = viewModelScope.launch { repo.checkNow() }
    fun downloadAndInstall(manifest: UpdateManifest) = viewModelScope.launch { repo.downloadAndInstall(manifest) }

    companion object {
        fun from(context: Context): UpdateViewModel =
            UpdateViewModel(UpdateRepository.from(context.applicationContext, BuildConfig.VERSION_CODE))
    }
}
