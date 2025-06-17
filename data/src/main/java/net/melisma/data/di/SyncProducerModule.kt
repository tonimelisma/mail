package net.melisma.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.data.sync.BackfillJobProducer
import net.melisma.data.sync.CacheEvictionProducer
import net.melisma.data.sync.JobProducer
import net.melisma.data.sync.BulkDownloadJobProducer

@Module
@InstallIn(SingletonComponent::class)
object SyncProducerModule {

    @Provides
    fun provideJobProducers(
        backfillJobProducer: BackfillJobProducer,
        cacheEvictionProducer: CacheEvictionProducer,
        bulkDownloadJobProducer: BulkDownloadJobProducer
    ): List<JobProducer> {
        return listOf(
            backfillJobProducer,
            cacheEvictionProducer,
            bulkDownloadJobProducer
        )
    }
} 