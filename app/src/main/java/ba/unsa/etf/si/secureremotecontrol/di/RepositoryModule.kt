package ba.unsa.etf.si.secureremotecontrol.di

import ba.unsa.etf.si.secureremotecontrol.data.repository.DeviceRepository
import ba.unsa.etf.si.secureremotecontrol.data.repository.DeviceRepositoryImpl
import ba.unsa.etf.si.secureremotecontrol.data.repository.SessionRepository
import ba.unsa.etf.si.secureremotecontrol.data.repository.SessionRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindDeviceRepository(
        deviceRepositoryImpl: DeviceRepositoryImpl
    ): DeviceRepository

    @Binds
    @Singleton
    abstract fun bindSessionRepository(
        sessionRepositoryImpl: SessionRepositoryImpl
    ): SessionRepository
} 