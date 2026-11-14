#pragma once

#include <QByteArray>

class MicPacket
{
public:
    static constexpr int FrameBytes = 640;
    MicPacket(const QByteArray& sessionId, const QByteArray& key);
    ~MicPacket();
    QByteArray encode(const QByteArray& pcm);
    bool acceptAcknowledgement(const QByteArray& packet);

private:
    QByteArray m_Id, m_Key, m_Prefix;
    quint64 m_Sequence = 0, m_Acknowledged = 0;
};
