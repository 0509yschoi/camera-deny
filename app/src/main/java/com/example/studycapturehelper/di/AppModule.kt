package com.example.studycapturehelper.di

import com.example.studycapturehelper.data.AndroidSpeechOutput
import com.example.studycapturehelper.data.AndroidThermalPolicy
import com.example.studycapturehelper.data.ApiTokenProvider
import com.example.studycapturehelper.data.GitHubAppUpdateRepository
import com.example.studycapturehelper.data.GitHubReleaseApi
import com.example.studycapturehelper.BuildConfig
import com.example.studycapturehelper.data.OpenAiApi
import com.example.studycapturehelper.data.OpenAiImageAnalyzer
import com.example.studycapturehelper.data.SettingsRepositoryImpl
import com.example.studycapturehelper.data.UvcCameraCapture
import com.example.studycapturehelper.domain.CameraCapture
import com.example.studycapturehelper.domain.AppUpdateRepository
import com.example.studycapturehelper.domain.ImageAnalyzer
import com.example.studycapturehelper.domain.SettingsRepository
import com.example.studycapturehelper.domain.SpeechOutput
import com.example.studycapturehelper.domain.ThermalPolicy
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun bindSettings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun bindCamera(impl: UvcCameraCapture): CameraCapture
    @Binds abstract fun bindAnalyzer(impl: OpenAiImageAnalyzer): ImageAnalyzer
    @Binds abstract fun bindSpeech(impl: AndroidSpeechOutput): SpeechOutput
    @Binds abstract fun bindThermal(impl: AndroidThermalPolicy): ThermalPolicy
    @Binds abstract fun bindAppUpdate(impl: GitHubAppUpdateRepository): AppUpdateRepository
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideOpenAiApi(client: OkHttpClient, moshi: Moshi): OpenAiApi =
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenAiApi::class.java)

    @Provides
    @Singleton
    fun provideGitHubReleaseApi(client: OkHttpClient, moshi: Moshi): GitHubReleaseApi =
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GitHubReleaseApi::class.java)

    @Provides
    @Singleton
    fun provideTokenProvider(): ApiTokenProvider = ApiTokenProvider {
        BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() }
    }
}
