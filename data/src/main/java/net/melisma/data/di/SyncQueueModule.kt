package net.melisma.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.PriorityBlockingQueue
import javax.inject.Singleton
import net.melisma.core_data.model.SyncJob

@Module
@InstallIn(SingletonComponent::class)
object SyncQueueModule {
    @Provides
    @Singleton
    fun provideSyncJobQueue(): PriorityBlockingQueue<SyncJob> = PriorityBlockingQueue()
} 