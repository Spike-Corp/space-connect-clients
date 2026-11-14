#pragma once

#include <QThread>
#include <QString>
#include <atomic>

// Managed hosts require authenticated acknowledgements before capture.
// Manual PCs retain the original companion protocol.
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
    void startForwarding(const QString& hostAddress, quint16 port = DEFAULT_PORT,
                        const QString& captureDeviceName = QString(), const QByteArray& sessionId = {},
                        const QByteArray& key = {});

    // Stops capturing and blocks until the worker thread has fully exited.
    void stopForwarding();

    bool isForwarding() const { return m_Running.load(); }

    // Normalized microphone input level, roughly 0.0 (silence) to 1.0 (loud). Reserved for a
    // future in-stream meter - not currently surfaced in the UI, but kept for parity with the
    // Android client's MicForwarder and to make adding one later trivial.
    float currentLevel() const { return m_CurrentLevel.load(); }

protected:
    void run() override;

signals:
    void failed(QString message);

private:
    QString m_HostAddress;
    quint16 m_Port;
    QString m_CaptureDeviceName;
    QByteArray m_SessionId, m_Key;
    std::atomic<bool> m_Requested{false};
    std::atomic<bool> m_Running;
    std::atomic<float> m_CurrentLevel;
};
