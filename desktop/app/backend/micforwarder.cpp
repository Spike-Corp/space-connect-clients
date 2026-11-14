#include "micforwarder.h"
#include "micpacket.h"

#include <SDL.h>
#include <QUdpSocket>
#include <QHostAddress>
#include <QElapsedTimer>
#include <QtEndian>
#include <QtDebug>
#include <cmath>
#include <memory>
#include <stdexcept>

MicForwarder::MicForwarder(QObject* parent)
    : QThread(parent), m_Port(DEFAULT_PORT), m_Running(false), m_CurrentLevel(0.0f)
{
}

MicForwarder::~MicForwarder()
{
    stopForwarding();
}

void MicForwarder::startForwarding(const QString& hostAddress, quint16 port, const QString& captureDeviceName,
                                  const QByteArray& sessionId, const QByteArray& key)
{
    if (isRunning()) return;
    m_HostAddress = hostAddress;
    m_Port = port;
    m_CaptureDeviceName = captureDeviceName;
    m_SessionId = sessionId;
    m_Key = key;
    m_Requested.store(true);
    start();
}

void MicForwarder::stopForwarding()
{
    m_Requested.store(false);
    wait();
}

void MicForwarder::run()
{
    SDL_AudioDeviceID device = 0;
    try {
        QUdpSocket socket;
        const QHostAddress address(m_HostAddress);
        if (address.isNull()) throw std::runtime_error("Invalid microphone host address");
        socket.connectToHost(address, m_Port);
        std::unique_ptr<MicPacket> secure;
        if (!m_SessionId.isEmpty() || !m_Key.isEmpty())
            secure.reset(new MicPacket(m_SessionId, m_Key));
        auto send = [&](const QByteArray& pcm) {
            QByteArray packet;
            if (secure) packet = secure->encode(pcm);
            else {
                packet = QByteArray::fromHex("53434d4201010000803e0000");
                packet.append(pcm);
            }
            if (socket.write(packet) != packet.size())
                throw std::runtime_error("Microphone datagram could not be sent");
        };
        auto receive = [&]() {
            bool acknowledged = false;
            for (int count = 0; count < 16 && socket.hasPendingDatagrams(); count++) {
                if (socket.pendingDatagramSize() != 65) {
                    socket.readDatagram(nullptr, 0);
                    continue;
                }
                QByteArray ack(65, '\0');
                if (socket.readDatagram(ack.data(), ack.size()) == 65 && secure->acceptAcknowledgement(ack))
                    acknowledged = true;
            }
            return acknowledged;
        };
        QElapsedTimer clock;
        clock.start();
        if (secure) {
            bool ready = false;
            while (m_Requested.load() && clock.elapsed() < 3000) {
                send({});
                socket.waitForReadyRead(50);
                if (receive()) { ready = true; break; }
            }
            if (m_Requested.load() && !ready)
                throw std::runtime_error("Host did not acknowledge the microphone");
        }
        if (!m_Requested.load()) { m_Key.fill('\0'); return; }

        SDL_AudioSpec desired;
        SDL_zero(desired);
        desired.freq = 16000;
        desired.format = AUDIO_S16LSB;
        desired.channels = 1;
        desired.samples = 320;
        SDL_AudioSpec obtained;
        const auto deviceName = m_CaptureDeviceName.toUtf8();
        device = SDL_OpenAudioDevice(deviceName.isEmpty() ? nullptr : deviceName.constData(),
                                     SDL_TRUE, &desired, &obtained, 0);
        if (!device) throw std::runtime_error(SDL_GetError());
        if (m_Requested.load()) {
            SDL_PauseAudioDevice(device, 0);
            m_Running.store(true);
        }
        qint64 lastAcknowledged = clock.elapsed(), lastProbe = lastAcknowledged;
        while (m_Requested.load()) {
            if (secure) {
                if (receive()) lastAcknowledged = clock.elapsed();
                if (clock.elapsed() - lastAcknowledged > 4000)
                    throw std::runtime_error("Host stopped acknowledging microphone audio");
                if (clock.elapsed() - lastProbe > 500) {
                    send({});
                    lastProbe = clock.elapsed();
                }
            }
            const Uint32 queued = SDL_GetQueuedAudioSize(device);
            if (!queued) { msleep(5); continue; }
            if (queued > 32000 / 10) {
                SDL_ClearQueuedAudio(device);
                continue;
            }
            QByteArray pcm(int(qMin<Uint32>(queued, MicPacket::FrameBytes)), '\0');
            pcm.resize(int(SDL_DequeueAudio(device, pcm.data(), Uint32(pcm.size()))));
            if (pcm.isEmpty()) continue;
            double sum = 0;
            for (int i = 0; i + 1 < pcm.size(); i += 2) {
                const double sample = qFromLittleEndian<qint16>(
                    reinterpret_cast<const unsigned char*>(pcm.constData() + i)) / 32768.0;
                sum += sample * sample;
            }
            m_CurrentLevel.store(qMin(1.0f, float(std::sqrt(sum / (pcm.size() / 2)) * 6.0)));
            send(pcm);
        }
    } catch (const std::exception& error) {
        m_Running.store(false);
        m_CurrentLevel.store(0.0f);
        if (m_Requested.load()) {
            qWarning() << "MicForwarder:" << error.what();
            emit failed(tr("Could not forward microphone audio. Check the input device and the host connection."));
        }
    }
    if (device) SDL_CloseAudioDevice(device);
    m_Requested.store(false);
    m_Running.store(false);
    m_CurrentLevel.store(0.0f);
    m_Key.fill('\0');
}
