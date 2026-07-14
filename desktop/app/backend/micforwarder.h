#pragma once

#include <QThread>
#include <QString>
#include <atomic>

// Captures the local microphone and forwards it as raw PCM over UDP to the "Space Connect Mic
// Bridge" companion program running on the STREAMING HOST (see /SpaceConnectMicBridge in the
// repo root) - the PC-client equivalent of the Android app's MicForwarder feature. Same wire
// protocol on both ends: a 12-byte header (magic "SCMB" + version + channels + reserved +
// little-endian sample rate) followed by raw 16kHz mono 16-bit PCM, sent to UDP port 48100 by
// default.
//
// Runs its own dedicated QThread (rather than SDL's own audio callback thread or the Qt main
// thread) so audio capture, packet construction, and the UDP socket all stay on one consistent
// thread with no cross-thread QObject/signal concerns - SDL's audio device is opened WITHOUT a
// callback (queued/pull mode), and this thread's run() loop drains it with SDL_DequeueAudio().
class MicForwarder : public QThread
{
    Q_OBJECT

public:
    static const quint16 DEFAULT_PORT = 48100;

    explicit MicForwarder(QObject* parent = nullptr);
    ~MicForwarder() override;

    // Begins capturing and forwarding to the given host. No-op if already running.
    // captureDeviceName selects a specific recording device by its SDL name (as returned by
    // SystemProperties::getAudioCaptureDeviceNames()) - pass an empty string to use the
    // system's default recording device instead.
    void startForwarding(const QString& hostAddress, quint16 port = DEFAULT_PORT, const QString& captureDeviceName = QString());

    // Stops capturing and blocks until the worker thread has fully exited.
    void stopForwarding();

    bool isForwarding() const { return m_Running.load(); }

    // Normalized microphone input level, roughly 0.0 (silence) to 1.0 (loud). Reserved for a
    // future in-stream meter - not currently surfaced in the UI, but kept for parity with the
    // Android client's MicForwarder and to make adding one later trivial.
    float currentLevel() const { return m_CurrentLevel.load(); }

protected:
    void run() override;

private:
    QString m_HostAddress;
    quint16 m_Port;
    QString m_CaptureDeviceName;
    std::atomic<bool> m_Running;
    std::atomic<float> m_CurrentLevel;
};
