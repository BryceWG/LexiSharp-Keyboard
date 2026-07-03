// Defines ASR engine construction intent without caller lifecycle details.
package com.brycewg.asrkb.asr

internal enum class AsrEngineAudioInput {
    MicrophoneCapture,
    PushedPcm
}

internal enum class AsrEngineParallelRole {
    Primary,
    Backup
}

internal enum class AsrRequestDurationReporting {
    ReportIfCallbackPresent,
    Suppressed
}

internal enum class AsrConfigurationValidation {
    FactoryRequired,
    CallerPrevalidated
}

internal enum class AsrEngineInvocationMode(
    val audioInput: AsrEngineAudioInput,
    val parallelRole: AsrEngineParallelRole?,
    val requestDurationReporting: AsrRequestDurationReporting,
    val configurationValidation: AsrConfigurationValidation
) {
    DirectMicrophoneCapture(
        audioInput = AsrEngineAudioInput.MicrophoneCapture,
        parallelRole = null,
        requestDurationReporting = AsrRequestDurationReporting.ReportIfCallbackPresent,
        configurationValidation = AsrConfigurationValidation.FactoryRequired
    ),
    PushPcm(
        audioInput = AsrEngineAudioInput.PushedPcm,
        parallelRole = null,
        requestDurationReporting = AsrRequestDurationReporting.ReportIfCallbackPresent,
        configurationValidation = AsrConfigurationValidation.FactoryRequired
    ),
    RecordingTest(
        audioInput = AsrEngineAudioInput.PushedPcm,
        parallelRole = null,
        requestDurationReporting = AsrRequestDurationReporting.Suppressed,
        configurationValidation = AsrConfigurationValidation.FactoryRequired
    ),
    ParallelPrimary(
        audioInput = AsrEngineAudioInput.PushedPcm,
        parallelRole = AsrEngineParallelRole.Primary,
        requestDurationReporting = AsrRequestDurationReporting.ReportIfCallbackPresent,
        configurationValidation = AsrConfigurationValidation.FactoryRequired
    ),
    ParallelBackup(
        audioInput = AsrEngineAudioInput.PushedPcm,
        parallelRole = AsrEngineParallelRole.Backup,
        requestDurationReporting = AsrRequestDurationReporting.Suppressed,
        configurationValidation = AsrConfigurationValidation.FactoryRequired
    );

    val ownsMicrophoneCapture: Boolean
        get() = audioInput == AsrEngineAudioInput.MicrophoneCapture

    val consumesPushedPcm: Boolean
        get() = audioInput == AsrEngineAudioInput.PushedPcm

    val reportsRequestDuration: Boolean
        get() = requestDurationReporting == AsrRequestDurationReporting.ReportIfCallbackPresent

    val requiresConfigurationValidation: Boolean
        get() = configurationValidation == AsrConfigurationValidation.FactoryRequired
}
