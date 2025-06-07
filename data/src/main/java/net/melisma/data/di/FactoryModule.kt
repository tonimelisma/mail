package net.melisma.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.core_data.datasource.MailApiServiceFactory
import net.melisma.data.datasource.MailApiServiceFactoryImpl 

@Module
@InstallIn(SingletonComponent::class)
abstract class FactoryModule {

    @Binds
    abstract fun bindMailApiServiceFactory(
        impl: MailApiServiceFactoryImpl
    ): MailApiServiceFactory
} 