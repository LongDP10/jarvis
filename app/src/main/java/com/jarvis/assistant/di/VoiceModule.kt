package com.jarvis.assistant.di

import com.jarvis.assistant.voice.SpeechWakeWordEngine
import com.jarvis.assistant.voice.WakeWordEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {

    /**
     * Swap this binding for a Porcupine-backed engine and nothing else in the
     * app needs to change.
     */
    @Binds
    @Singleton
    abstract fun bindWakeWordEngine(impl: SpeechWakeWordEngine): WakeWordEngine
}
