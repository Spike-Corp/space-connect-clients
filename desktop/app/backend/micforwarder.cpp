#include "micforwarder.h"

#include <SDL.h>
#include <QUdpSocket>
#include <QHostAddress>
#include <QtEndian>
#include <QtDebug>

#include <cmath>
#include <cstring>
#include <algorithm>

#define MIC_SAMPLE_RATE 16000
#define MIC_CHANNELS 1
#define MIC_PROTOCOL_VERSION 1
#define MIC_HEADER_SIZE 12

MicForwarder::MicForwarder(QObject* parent)
    : QThread(parent),
      m_Port(DEFAULT_PORT),
      m_Running(false),
      m_CurrentLevel(0.0f)
{
}

MicForwarder::~MicForwarder()
{
    stopForwarding();
}

void MicForwarder::startForwarding(const QString& hostAddress, quint16 port, const QString& captureDeviceName)
{
    if (m_Running.load()) {
        return;
    }

    m_HostAddress = hostAddress;
    m_Port = port;
    m_CaptureDeviceName = captureDeviceName;
    m_Running.store(true);

    start();
}

void MicForwarder::stopForwarding()
{
    if (!m_Running.load()) {
        return;
    }

    m_Running.store(false);
    wait(1000);
}

void MicForwarder::run()
{
    SDL_AudioSpec desired;
    SDL_zero(desired);
    desired.freq = MIC_SAMPLE_RATE;
    desired.format = AUDIO_S16LSB;
    desired.channels = MIC_CHANNELS;
    desired.samples = 1024;
    // No callback is set here deliberately - this puts the device in "queued" mode so we can
    // safely pull captured audio from this thread's own loop below via SDL_DequeueAudio(),
    // rather than dealing with SDL's separate internal audio callback thread.

    SDL_AudioSpec obtained;
    // A null device name means "use the system default recording device" - SDL_OpenAudioDevice
    // treats a NULL first argument the same way, so this maps naturally without a branch.
    QByteArray deviceNameUtf8 = m_CaptureDeviceName.toUtf8();
    const char* deviceName = deviceNameUtf8.isEmpty() ? nullptr : deviceNameUtf8.constData();
    SDL_AudioDeviceID device = SDL_OpenAudioDevice(deviceName, SDL_TRUE, &desired, &obtained, 0);
    if (device == 0) {
        qWarning() << "MicForwarder: failed to open microphone capture device:" << SDL_GetError();
        m_Running.store(false);
        return;
    }

    QUdpSocket socket;
    QHostAddress address(m_HostAddress);

    quint8 header[MIC_HEADER_SIZE];
    memset(header, 0, sizeof(header));
    header[0] = 'S';
    header[1] = 'C';
    header[2] = 'M';
    header[3] = 'B';
    header[4] = MIC_PROTOCOL_VERSION;
    header[5] = MIC_CHANNELS;
    // bytes 6-7 reserved, left zeroed
    quint32 sampleRateLE = qToLittleEndian<quint32>(MIC_SAMPLE_RATE);
    memcpy(&header[8], &sampleRateLE, sizeof(sampleRateLE));

    SDL_PauseAudioDevice(device, 0);

    qInfo() << "MicForwarder: forwarding microphone audio to" << m_HostAddress << ":" << m_Port;

    const Uint32 k_MaxChunkBytes = 8192;

    while (m_Running.load()) {
        Uint32 queuedBytes = SDL_GetQueuedAudioSize(device);
        if (queuedBytes == 0) {
            SDL_Delay(10);
            continue;
        }

        Uint32 toRead = std::min(queuedBytes, k_MaxChunkBytes);

        QByteArray audioData(static_cast<int>(toRead), Qt::Uninitialized);
        Uint32 actuallyRead = SDL_DequeueAudio(device, audioData.data(), toRead);
        if (actuallyRead == 0) {
            continue;
        }
        audioData.resize(static_cast<int>(actuallyRead));

        // Simple RMS-based level meter over the PCM16LE buffer, normalized to ~0.0-1.0 with a
        // gain multiplier since raw speech RMS is far below full-scale digital amplitude -
        // matches the Android MicForwarder's computeLevel() exactly.
        const qint16* samples = reinterpret_cast<const qint16*>(audioData.constData());
        int sampleCount = audioData.size() / 2;
        double sumSquares = 0;
        for (int i = 0; i < sampleCount; i++) {
            double normalized = samples[i] / 32768.0;
            sumSquares += normalized * normalized;
        }
        float rms = sampleCount > 0 ? static_cast<float>(sqrt(sumSquares / sampleCount)) : 0.0f;
        m_CurrentLevel.store(std::min(1.0f, std::max(0.0f, rms * 6.0f)));

        QByteArray packet;
        packet.reserve(MIC_HEADER_SIZE + audioData.size());
        packet.append(reinterpret_cast<const char*>(header), MIC_HEADER_SIZE);
        packet.append(audioData);

        socket.writeDatagram(packet, address, m_Port);
    }

    SDL_CloseAudioDevice(device);
    m_CurrentLevel.store(0.0f);

    qInfo() << "MicForwarder: stopped forwarding";
}
