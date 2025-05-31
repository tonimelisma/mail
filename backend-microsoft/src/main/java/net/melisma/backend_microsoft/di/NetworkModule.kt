package net.melisma.backend_microsoft.di

// import okhttp3.OkHttpClient // Only needed if customizing OkHttp client directly
// import okhttp3.logging.HttpLoggingInterceptor // Only needed if using OkHttp interceptor logging
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import timber.log.Timber

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideKtorHttpClient(json: Json): HttpClient {
        return HttpClient(OkHttp) {
            // Configure OkHttp engine
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(30, TimeUnit.SECONDS)
                    // Example: Add OkHttp logging interceptor if preferred over Ktor's
                    // val loggingInterceptor = HttpLoggingInterceptor()
                    // loggingInterceptor.level = HttpLoggingInterceptor.Level.BODY
                    // addInterceptor(loggingInterceptor)
                }
            }

            // Install Ktor features (plugins)
            install(ContentNegotiation) {
                json(json) // Use Kotlinx Serialization
            }

            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        // Only log the first non-blank line of the message
                        val firstLine = message.lines().firstOrNull { it.isNotBlank() } ?: message
                        Timber.tag("KtorHttpClient").v(firstLine) // Log to Android Logcat
                    }
                }
                level =
                    LogLevel.INFO // Keep INFO, filtering is now in the logger
            }

            // Optional: Throw exceptions for non-2xx responses
            // expectSuccess = true
        }
    }
}
