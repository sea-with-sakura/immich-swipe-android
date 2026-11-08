package com.minos2020.immichswipe.feature.local

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.minos2020.immichswipe.core.SessionConfig
import com.minos2020.immichswipe.feature.local.transfer.*
import com.minos2020.immichswipe.data.api.RetrofitFactory
import com.minos2020.immichswipe.data.repository.AlbumRepository
import com.minos2020.immichswipe.data.repository.LocalMediaRepository
import com.minos2020.immichswipe.data.repository.LocalTransferRepository

class LocalOrganizerViewModelFactory(context: Context, private val config: SessionConfig, private val albums: AlbumRepository) : ViewModelProvider.Factory {
    private val application = context.applicationContext
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val media = LocalMediaRepository(application)
        return LocalOrganizerViewModel(media, LocalTransferRepository(media, RetrofitFactory.create(config, uploadTimeouts = true), config), albums, PersistentLocalTransferQueue(application, config.localAccount())) as T
    }
}
