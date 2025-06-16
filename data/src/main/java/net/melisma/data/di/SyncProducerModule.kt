package net.melisma.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.data.sync.BackfillJobProducer
import net.melisma.data.sync.CacheEvictionProducer
import net.melisma.data.sync.JobProducer

@Module
@InstallIn(SingletonComponent::class)
object SyncProducerModule {

    @Provides
    fun provideJobProducers(
        backfillJobProducer: BackfillJobProducer,
        cacheEvictionProducer: CacheEvictionProducer
    ): List<JobProducer> {
        return listOf(
            backfillJobProducer,
            cacheEvictionProducer
        )
    }
} 