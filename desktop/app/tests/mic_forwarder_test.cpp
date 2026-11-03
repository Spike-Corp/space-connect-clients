#include <QtTest>
#include <QUdpSocket>
#define SDL_MAIN_HANDLED
#include <SDL.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include "../backend/micforwarder.h"

class MicForwarderTest : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase()
    {
        qputenv("SDL_AUDIODRIVER", "dummy");
        SDL_SetMainReady();
        QCOMPARE(SDL_InitSubSystem(SDL_INIT_AUDIO), 0);
    }

    void doesNotCaptureWithoutAHostAcknowledgement()
    {
        QUdpSocket receiver;
        QVERIFY(receiver.bind(QHostAddress(QHostAddress::LocalHost), 0));
        MicForwarder forwarder;
        QSignalSpy failure(&forwarder, &MicForwarder::failed);
        forwarder.startForwarding("127.0.0.1", receiver.localPort(), {}, QByteArray(16, '\x20'), QByteArray(32, '\x40'));
        QTRY_VERIFY(receiver.hasPendingDatagrams());
        QVERIFY(!forwarder.isForwarding());
        QTRY_VERIFY_WITH_TIMEOUT(failure.count() == 1, 4500);
        QVERIFY(!forwarder.isForwarding());
        forwarder.stopForwarding();
        QVERIFY(!forwarder.isRunning());
    }

    void capturesAfterAckAndStopsWhenTheHostStopsAcknowledging()
    {
        QUdpSocket receiver;
        QVERIFY(receiver.bind(QHostAddress(QHostAddress::LocalHost), 0));
        MicForwarder forwarder;
        QSignalSpy failure(&forwarder, &MicForwarder::failed);
        const QByteArray key(32, '\x40');
        forwarder.startForwarding("127.0.0.1", receiver.localPort(), {}, QByteArray(16, '\x20'), key);
        QTRY_VERIFY(receiver.hasPendingDatagrams());
        QByteArray probe(int(receiver.pendingDatagramSize()), '\0');
        QHostAddress sender;
        quint16 port;
        receiver.readDatagram(probe.data(), probe.size(), &sender, &port);
        QCOMPARE(probe.size(), 48);
        auto ack = QByteArray("SCA2") + probe.mid(4, 28) + QByteArray(1, '\1');
        unsigned char digest[EVP_MAX_MD_SIZE];
        unsigned int size = 0;
        HMAC(EVP_sha256(), key.constData(), key.size(),
            reinterpret_cast<const unsigned char*>(ack.constData()), ack.size(), digest, &size);
        ack.append(reinterpret_cast<const char*>(digest), int(size));
        QCOMPARE(receiver.writeDatagram(ack, sender, port), qint64(65));
        QTRY_VERIFY(forwarder.isForwarding());
        QTRY_VERIFY_WITH_TIMEOUT(failure.count() == 1, 5500);
        QVERIFY(!forwarder.isForwarding());
        forwarder.stopForwarding();
        QVERIFY(!forwarder.isRunning());
    }

    void cancellationStopsHandshakeBeforeTheThreadIsDestroyed()
    {
        QUdpSocket receiver;
        QVERIFY(receiver.bind(QHostAddress(QHostAddress::LocalHost), 0));
        MicForwarder forwarder;
        QSignalSpy failure(&forwarder, &MicForwarder::failed);
        forwarder.startForwarding("127.0.0.1", receiver.localPort(), {}, QByteArray(16, '\x20'), QByteArray(32, '\x40'));
        QTRY_VERIFY(receiver.hasPendingDatagrams());
        forwarder.stopForwarding();
        QVERIFY(!forwarder.isRunning());
        QVERIFY(!forwarder.isForwarding());
        QCOMPARE(failure.count(), 0);
    }

    void cleanupTestCase() { SDL_QuitSubSystem(SDL_INIT_AUDIO); }
};

QTEST_GUILESS_MAIN(MicForwarderTest)
#include "mic_forwarder_test.moc"
