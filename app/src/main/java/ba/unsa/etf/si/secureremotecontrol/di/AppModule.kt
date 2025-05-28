package ba.unsa.etf.si.secureremotecontrol.di

import ba.unsa.etf.si.secureremotecontrol.data.network.ApiService
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import ba.unsa.etf.si.secureremotecontrol.data.websocket.WebSocketServiceImpl
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import ba.unsa.etf.si.secureremotecontrol.data.util.RegistrationPreferences

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton // Ensures only one instance is created
    fun provideRegistrationPreferences(@ApplicationContext context: Context): RegistrationPreferences {
        // Hilt provides the application context automatically
        return RegistrationPreferences(context)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, gson: Gson, registrationPreferences: RegistrationPreferences): Retrofit {
        // The base URL for Retrofit might still be needed for HTTP API calls,
        // or could also be made dynamic if necessary, but usually it's more static than WebSocket URLs.
        // For now, keeping the original static Retrofit base URL.
        // If you need the WebSocket URL for Retrofit as well, you'd need to adjust this.
        // However, Retrofit typically does not handle WebSockets directly.
        return Retrofit.Builder()
            .baseUrl("https://remote-control-gateway-production.up.railway.app/") // This is for HTTP API, not WebSocket
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideWebSocketService(
        client: OkHttpClient,
        gson: Gson,
        registrationPreferences: RegistrationPreferences // Inject RegistrationPreferences
    ): WebSocketService {
        return WebSocketServiceImpl(client, gson, registrationPreferences) // Pass to constructor
    }

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }
}