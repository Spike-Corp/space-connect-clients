#include "micpacket.h"

#include <QtEndian>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>
#include <limits>
#include <memory>
#include <stdexcept>

MicPacket::MicPacket(const QByteArray& sessionId, const QByteArray& key)
    : m_Id(sessionId), m_Key(key), m_Prefix(4, '\0')
{
    if (m_Id.size() != 16 || m_Key.size() != 32)
        throw std::invalid_argument("Invalid microphone session");
    if (RAND_bytes(reinterpret_cast<unsigned char*>(m_Prefix.data()), 4) != 1)
        throw std::runtime_error("Microphone random source unavailable");
}

MicPacket::~MicPacket()
{
    OPENSSL_cleanse(m_Key.data(), size_t(m_Key.size()));
}

QByteArray MicPacket::encode(const QByteArray& pcm)
{
    if (pcm.size() > FrameBytes || pcm.size() % 2 != 0
            || m_Sequence == std::numeric_limits<quint64>::max())
        throw std::invalid_argument("Invalid microphone frame");
    QByteArray packet = QByteArray("SCM2") + m_Id + m_Prefix;
    const quint64 sequence = qToLittleEndian(++m_Sequence);
    packet.append(reinterpret_cast<const char*>(&sequence), 8);
    packet.resize(48 + pcm.size());
    std::unique_ptr<EVP_CIPHER_CTX, decltype(&EVP_CIPHER_CTX_free)> context(EVP_CIPHER_CTX_new(), EVP_CIPHER_CTX_free);
    int count = 0, finalCount = 0;
    auto* bytes = reinterpret_cast<unsigned char*>(packet.data());
    if (!context || EVP_EncryptInit_ex(context.get(), EVP_aes_256_gcm(), nullptr,
            reinterpret_cast<const unsigned char*>(m_Key.constData()), bytes + 20) != 1
            || EVP_EncryptUpdate(context.get(), nullptr, &count, bytes, 32) != 1
            || EVP_EncryptUpdate(context.get(), bytes + 32, &count,
                reinterpret_cast<const unsigned char*>(pcm.constData()), int(pcm.size())) != 1
            || count != pcm.size()
            || EVP_EncryptFinal_ex(context.get(), bytes + 32 + count, &finalCount) != 1
            || finalCount != 0
            || EVP_CIPHER_CTX_ctrl(context.get(), EVP_CTRL_GCM_GET_TAG, 16, bytes + 32 + pcm.size()) != 1)
        throw std::runtime_error("Microphone encryption failed");
    return packet;
}

bool MicPacket::acceptAcknowledgement(const QByteArray& packet)
{
    if (packet.size() != 65 || packet.left(4) != "SCA2" || packet.mid(4, 16) != m_Id
            || packet.mid(20, 4) != m_Prefix || packet[32] != 1)
        return false;
    const quint64 sequence = qFromLittleEndian<quint64>(reinterpret_cast<const unsigned char*>(packet.constData() + 24));
    if (sequence <= m_Acknowledged || sequence > m_Sequence) return false;
    unsigned char digest[EVP_MAX_MD_SIZE];
    unsigned int length = 0;
    if (!HMAC(EVP_sha256(), m_Key.constData(), int(m_Key.size()),
            reinterpret_cast<const unsigned char*>(packet.constData()), 33, digest, &length)
            || length != 32 || CRYPTO_memcmp(digest, packet.constData() + 33, 32) != 0)
        return false;
    m_Acknowledged = sequence;
    return true;
}
