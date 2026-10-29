#include <QtTest>
#include <QScopeGuard>
#include <QSettings>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QTranslator>
#include <QUrlQuery>

#include <cstring>
#include <memory>
#include <openssl/rand.h>

#include "../backend/launcherjson.h"
#include "../backend/nvcomputer.h"
#include "../backend/nvpairingmanager.h"

class PairingHost
{
public:
    QTcpServer server;
    bool includeCertificate = false;
    int cleanupStatus = 404;
    int cleanupRequests = 0;
    QByteArray challenge;

    PairingHost()
    {
        QObject::connect(&server, &QTcpServer::newConnection, &server, [this]() {
            QTcpSocket* socket = server.nextPendingConnection();
            auto input = std::make_shared<QByteArray>();
            QObject::connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
            QObject::connect(socket, &QTcpSocket::readyRead, &server, [this, socket, input]() {
                input->append(socket->readAll());
                if (!input->contains("\r\n\r\n"))
                    return;
                const QUrl url = QUrl::fromEncoded(input->split(' ').at(1));
                const QUrlQuery query(url);
                int status = 200;
                QByteArray body = "<root status_code=\"200\"><paired>0</paired></root>";
                if (url.path() == QStringLiteral("/unpair")) {
                    cleanupRequests++;
                    status = cleanupStatus;
                }
                else if (query.queryItemValue(QStringLiteral("phrase")) == QStringLiteral("getservercert")) {
                    body = "<root status_code=\"200\"><paired>1</paired>";
                    if (includeCertificate) {
                        body += "<plaincert>" + IdentityManager::get()->getCertificate().toHex() + "</plaincert>";
                    }
                    body += "</root>";
                }
                else if (query.hasQueryItem(QStringLiteral("clientchallenge"))) {
                    challenge = query.queryItemValue(QStringLiteral("clientchallenge")).toLatin1();
                }
                socket->write("HTTP/1.1 " + QByteArray::number(status) + " Test\r\nContent-Length: "
                              + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
                socket->disconnectFromHost();
            });
        });
        server.listen(QHostAddress::LocalHost);
    }

    NvComputer computer() const
    {
        NvComputer result{};
        result.activeAddress = NvAddress(QStringLiteral("127.0.0.1"), server.serverPort());
        result.activeHttpsPort = server.serverPort();
        return result;
    }
};

class PairingTest : public QObject
{
    Q_OBJECT
    QTemporaryDir m_Settings;

private slots:
    void initTestCase()
    {
        QVERIFY(m_Settings.isValid());
        QCoreApplication::setOrganizationName(QStringLiteral("Space Connect Tests"));
        QCoreApplication::setApplicationName(QStringLiteral("Pairing"));
        QSettings::setDefaultFormat(QSettings::IniFormat);
        QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, m_Settings.path());
        IdentityManager::get();
    }

    void preservesOriginalFailure_data()
    {
        QTest::addColumn<int>("cleanupStatus");
        QTest::newRow("apollo-404") << 404;
        QTest::newRow("cleanup-500") << 500;
    }

    void preservesOriginalFailure()
    {
        QFETCH(int, cleanupStatus);
        PairingHost host;
        QVERIFY(host.server.isListening());
        host.cleanupStatus = cleanupStatus;
        NvComputer computer = host.computer();
        NvPairingManager pairing(&computer);
        QSslCertificate certificate;
        QCOMPARE(pairing.pair(QStringLiteral("7.1.431.1"), QStringLiteral("1234"), certificate),
                 NvPairingManager::ALREADY_IN_PROGRESS);
        QCOMPARE(host.cleanupRequests, 1);
        QVERIFY(certificate.isNull());
    }

    void preservesBinaryKeyWhenHashContainsZero()
    {
        PairingHost host;
        QVERIFY(host.server.isListening());
        host.includeCertificate = true;
        NvComputer computer = host.computer();
        NvPairingManager pairing(&computer);

        // A zero salt with test PIN 0007 yields a key whose byte 12 is zero.
        // Fix only the test's randomness, after creating the client identity.
        const RAND_METHOD* original = RAND_get_rand_method();
        QVERIFY(original != nullptr);
        RAND_METHOD fixed = *original;
        fixed.bytes = [](unsigned char* data, int length) {
            std::memset(data, 0, size_t(length));
            return 1;
        };
        const auto restoreRandom = qScopeGuard([original]() { RAND_set_rand_method(original); });
        QVERIFY(RAND_set_rand_method(&fixed) == 1);

        QSslCertificate certificate;
        QCOMPARE(pairing.pair(QStringLiteral("7.1.431.1"), QStringLiteral("0007"), certificate),
                 NvPairingManager::FAILED);
        // Independent AES-128-ECB vector: key 0788f6f794a1c805aa70acc900e89f9f,
        // plaintext = 16 zero bytes. The host deliberately rejects stage 2.
        QCOMPARE(host.challenge, QByteArray("e5ddda9903e0da26ef05e4f3d2a1778b"));
        QCOMPARE(host.cleanupRequests, 1);
        QVERIFY(certificate.isNull());
    }

    void recognizesPairPinDelivery_data()
    {
        QTest::addColumn<int>("status");
        QTest::addColumn<QByteArray>("json");
        QTest::addColumn<bool>("accepted");
        QTest::newRow("paired") << 200 << QByteArray(R"({"paired":true})") << true;
        QTest::newRow("queued-not-paired")
            << 200 << QByteArray(R"({"paired":false,"viaAgent":true})") << true;
        QTest::newRow("not-accepted") << 200 << QByteArray(R"({"paired":false})") << false;
        QTest::newRow("http-error")
            << 500 << QByteArray(R"({"paired":true,"viaAgent":true})") << false;
    }

    void recognizesPairPinDelivery()
    {
        QFETCH(int, status);
        QFETCH(QByteArray, json);
        QFETCH(bool, accepted);
        QCOMPARE(LauncherJson::pairPinAccepted(status, QJsonDocument::fromJson(json).object()), accepted);
    }

    void translatesReportedDialogs_data()
    {
        QTest::addColumn<QString>("locale");
        QTest::addColumn<QString>("context");
        QTest::addColumn<QString>("source");
        const QList<QPair<QString, QString>> messages = {
            {QStringLiteral("PcView"), QStringLiteral("Remove '%1' from this app?\n\nThis only removes the shortcut from your list \u2014 your VM in the cloud (and everything in it) is NOT deleted. You can add it back anytime.\n\nTo actually delete the VM and free its disk, use the panel (spacecloud.gg/painel).")},
            {QStringLiteral("main"), QStringLiteral("Update available for Space Connect: Version %1. Click to install now.")},
            {QStringLiteral("main"), QStringLiteral("Downloading update... %1%")},
            {QStringLiteral("main"), QStringLiteral("Download failed (%1). Opening browser...")},
        };
        for (const QString& locale : {QStringLiteral("pt_BR"), QStringLiteral("pt")}) {
            for (int i = 0; i < messages.size(); i++) {
                const QByteArray row = locale.toUtf8() + "-" + QByteArray::number(i);
                QTest::newRow(row.constData()) << locale << messages[i].first << messages[i].second;
            }
        }
    }

    void translatesReportedDialogs()
    {
        QFETCH(QString, locale);
        QFETCH(QString, context);
        QFETCH(QString, source);
        const QByteArray catalog = "../languages/qml_" + locale.toUtf8() + ".qm";
        const QByteArray qmlPath = "../gui/" + context.toUtf8() + ".qml";
        QFile qml(QFINDTESTDATA(qmlPath.constData()));
        QVERIFY(qml.open(QIODevice::ReadOnly));
        QString escaped = source;
        escaped.replace(QStringLiteral("\n"), QStringLiteral("\\n"));
        QVERIFY(QString::fromUtf8(qml.readAll()).contains(QStringLiteral("qsTr(\"") + escaped + QStringLiteral("\")")));

        QTranslator translator;
        QVERIFY(translator.load(QFINDTESTDATA(catalog.constData())));
        const QString translated = translator.translate(context.toUtf8().constData(), source.toUtf8().constData());
        QVERIFY(!translated.isEmpty());
        QVERIFY(translated != source);
        QCOMPARE(translated.count(QStringLiteral("%1")), source.count(QStringLiteral("%1")));
    }
};

QTEST_GUILESS_MAIN(PairingTest)
#include "pairing_test.moc"
