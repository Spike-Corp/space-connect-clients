#include "micleveltester.h"

#include <SDL.h>
#include <QtDebug>

#include <cmath>
#include <cstring>
#include <algorithm>

// Mesmos parâmetros do MicForwarder (16kHz mono S16) pra o nível medido aqui
// ser exatamente o que seria enviado pra VM.
#define MIC_SAMPLE_RATE 16000
#define MIC_CHANNELS 1

MicLevelTester::MicLevelTester(QObject* parent)
    : QThread(parent),
      m_Running(false),
      m_CurrentLevel(0.0f)
{
}

MicLevelTester::~MicLevelTester()
{
    stopTesting();
}

void MicLevelTester::startTesting(const QString& captureDeviceName)
{
    if (m_Running.load()) {
        return;
    }
    m_CaptureDeviceName = captureDeviceName;
    m_Running.store(true);
    emit testingChanged();
    start();
}

void MicLevelTester::stopTesting()
{
    if (!m_Running.load()) {
        return;
    }
    m_Running.store(false);
    wait(1000);
    emit testingChanged();
}

void MicLevelTester::run()
{
    SDL_AudioSpec desired;
    SDL_zero(desired);
    desired.freq = MIC_SAMPLE_RATE;
    desired.format = AUDIO_S16LSB;
    desired.channels = MIC_CHANNELS;
    desired.samples = 1024;

    SDL_AudioSpec obtained;
    QByteArray deviceNameUtf8 = m_CaptureDeviceName.toUtf8();
    const char* deviceName = deviceNameUtf8.isEmpty() ? nullptr : deviceNameUtf8.constData();
    SDL_AudioDeviceID device = SDL_OpenAudioDevice(deviceName, SDL_TRUE, &desired, &obtained, 0);
    if (device == 0) {
        qWarning() << "MicLevelTester: failed to open capture device:" << SDL_GetError();
        m_Running.store(false);
        return;
    }

    SDL_PauseAudioDevice(device, 0);

    const Uint32 k_MaxChunkBytes = 8192;
    while (m_Running.load()) {
        Uint32 queuedBytes = SDL_GetQueuedAudioSize(device);
        if (queuedBytes == 0) {
            SDL_Delay(30);
            continue;
        }

        Uint32 toRead = std::min(queuedBytes, k_MaxChunkBytes);
        QByteArray audioData(static_cast<int>(toRead), Qt::Uninitialized);
        Uint32 actuallyRead = SDL_DequeueAudio(device, audioData.data(), toRead);
        if (actuallyRead == 0) {
            continue;
        }

        // RMS normalizado com ganho — idêntico ao MicForwarder
        const qint16* samples = reinterpret_cast<const qint16*>(audioData.constData());
        int sampleCount = static_cast<int>(actuallyRead) / 2;
        double sumSquares = 0;
        for (int i = 0; i < sampleCount; i++) {
            double normalized = samples[i] / 32768.0;
            sumSquares += normalized * normalized;
        }
        float rms = sampleCount > 0 ? static_cast<float>(sqrt(sumSquares / sampleCount)) : 0.0f;
        float level = std::min(1.0f, std::max(0.0f, rms * 6.0f));
        m_CurrentLevel.store(level);
        emit levelChanged(level);
    }

    SDL_CloseAudioDevice(device);
    m_CurrentLevel.store(0.0f);
}
