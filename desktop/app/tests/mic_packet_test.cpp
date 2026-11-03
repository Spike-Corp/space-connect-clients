#include <QtTest>
#include <QtEndian>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <stdexcept>
#include "../backend/micpacket.h"

class MicPacketTest : public QObject
{
    Q_OBJECT
    const QByteArray id = QByteArray::fromHex("202122232425262728292a2b2c2d2e2f");
    const QByteArray key = QByteArray::fromHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

private slots:
    void encryptsRealPcmWithoutFragmenting()
    {
        MicPacket codec(id, key);
        const auto packet = codec.encode(QByteArray::fromHex("3412cced"));
        QCOMPARE(packet.size(), 52);
        QCOMPARE(packet.left(4), QByteArray("SCM2"));
        auto* context = EVP_CIPHER_CTX_new();
        QVERIFY(context);
        QCOMPARE(EVP_DecryptInit_ex(context, EVP_aes_256_gcm(), nullptr,
            reinterpret_cast<const unsigned char*>(key.constData()),
            reinterpret_cast<const unsigned char*>(packet.constData() + 20)), 1);
        int count = 0;
        QCOMPARE(EVP_DecryptUpdate(context, nullptr, &count,
            reinterpret_cast<const unsigned char*>(packet.constData()), 32), 1);
        QByteArray decoded(4, '\0');
        QCOMPARE(EVP_DecryptUpdate(context, reinterpret_cast<unsigned char*>(decoded.data()), &count,
            reinterpret_cast<const unsigned char*>(packet.constData() + 32), 4), 1);
        auto tag = packet.right(16);
        QCOMPARE(EVP_CIPHER_CTX_ctrl(context, EVP_CTRL_GCM_SET_TAG, 16, tag.data()), 1);
        unsigned char unused[16];
        QCOMPARE(EVP_DecryptFinal_ex(context, unused, &count), 1);
        EVP_CIPHER_CTX_free(context);
        QCOMPARE(decoded, QByteArray::fromHex("3412cced"));
        const auto next = codec.encode(QByteArray(640, '\0'));
        QCOMPARE(next.size(), 688);
        QVERIFY(packet.mid(20, 12) != next.mid(20, 12));
        bool rejected = false;
        try { codec.encode(QByteArray(641, '\0')); }
        catch (const std::invalid_argument&) { rejected = true; }
        QVERIFY(rejected);
    }

    void requiresAnAuthenticatedFreshHostAcknowledgement()
    {
        MicPacket codec(id, key);
        const auto packet = codec.encode({});
        QVERIFY(packet.size() >= 48);
        auto ack = QByteArray("SCA2") + packet.mid(4, 28) + QByteArray(1, '\1');
        unsigned char digest[EVP_MAX_MD_SIZE];
        unsigned int length = 0;
        QVERIFY(HMAC(EVP_sha256(), key.constData(), key.size(),
            reinterpret_cast<const unsigned char*>(ack.constData()), size_t(ack.size()), digest, &length));
        ack.append(reinterpret_cast<const char*>(digest), int(length));
        auto forged = ack;
        forged[64] = char(forged[64] ^ 1);
        QVERIFY(!codec.acceptAcknowledgement(forged));
        QVERIFY(codec.acceptAcknowledgement(ack));
        QVERIFY(!codec.acceptAcknowledgement(ack));
        MicPacket other(QByteArray(16, '\0'), key);
        other.encode({});
        QVERIFY(!other.acceptAcknowledgement(ack));
    }
};

QTEST_GUILESS_MAIN(MicPacketTest)
#include "mic_packet_test.moc"
