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
#include "../backend/desktoplaunch.h"

class PairingHost
{
public:
    QTcpServer server;
    bool includeCertificate = false;
    int cleanupStatus = 404;
    int cleanupRequests = 0;
    QByteArray challenge;
    bool stallRequests = false;
    int requests = 0;
    int disconnects = 0;
    int launchDenials = 0;
    int launchRequests = 0;
    QUrl lastLaunch;

    PairingHost()
    {
        QObject::connect(&server, &QTcpServer::newConnection, &server, [this]() {
            QTcpSocket* socket = server.nextPendingConnection();
            auto input = std::make_shared<QByteArray>();
            QObject::connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
            QObject::connect(socket, &QTcpSocket::disconnected, &server, [this]() { disconnects++; });
            QObject::connect(socket, &QTcpSocket::readyRead, &server, [this, socket, input]() {
                input->append(socket->readAll());
                if (!input->contains("\r\n\r\n"))
                    return;
                requests++;
                if (stallRequests)
                    return;
                const QUrl url = QUrl::fromEncoded(input->split(' ').at(1));
                const QUrlQuery query(url);
                int status = 200;
                QByteArray body = "<root status_code=\"200\"><paired>0</paired></root>";
                if (url.path() == QStringLiteral("/launch")) {
                    lastLaunch = url;
                    launchRequests++;
                    if (launchRequests <= launchDenials)
                        body = "<root status_code=\"403\" status_message=\"Permission pending\"/>";
                    else
                        body = "<root status_code=\"200\"><sessionUrl0>rtsp://127.0.0.1/fixture</sessionUrl0></root>";
                }
                else if (url.path() == QStringLiteral("/unpair")) {
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

    void abortsPendingNativeHandshake()
    {
        PairingHost host;
        host.stallRequests = true;
        NvComputer computer = host.computer();
        const auto cancelled = std::make_shared<std::atomic_bool>(false);
        NvPairingManager pairing(&computer, cancelled);
        QSslCertificate certificate;
        QTimer::singleShot(60, this, [cancelled]() { cancelled->store(true); });
        QElapsedTimer elapsed;
        elapsed.start();
        try {
            pairing.pair(QStringLiteral("7.1.431.1"), QStringLiteral("1234"), certificate);
            QFAIL("Pending pairing must be interrupted");
        }
        catch (const QtNetworkReplyException& error) {
            QCOMPARE(error.getError(), QNetworkReply::OperationCanceledError);
        }
        QVERIFY(elapsed.elapsed() < 1000);
        QCOMPARE(host.requests, 1);
        QTRY_COMPARE(host.disconnects, 1);
        QVERIFY(certificate.isNull());
    }

    void cancelledAttemptNeverContactsHost()
    {
        PairingHost host;
        NvComputer computer = host.computer();
        const auto cancelled = std::make_shared<std::atomic_bool>(true);
        NvPairingManager pairing(&computer, cancelled);
        QSslCertificate certificate;
        try {
            pairing.pair(QStringLiteral("7.1.431.1"), QStringLiteral("1234"), certificate);
            QFAIL("An obsolete attempt must not pair");
        }
        catch (const QtNetworkReplyException& error) {
            QCOMPARE(error.getError(), QNetworkReply::OperationCanceledError);
        }
        QCOMPARE(host.requests, 0);
    }

    void selectsRealDesktopIdWithoutGameFallback()
    {
        NvApp game;
        game.name = QStringLiteral("Steam");
        game.id = 99;
        game.directLaunch = true;
        NvApp desktop;
        desktop.name = QStringLiteral("Desktop");
        desktop.id = 4321;
        QVector<NvApp> apps{game, desktop};
        const int chosen = DesktopLaunch::appIndex(apps);
        QCOMPARE(chosen, 1);
        QCOMPARE(apps[chosen].id, 4321);
        QCOMPARE(DesktopLaunch::appIndex(QVector<NvApp>{game}), -1);
        desktop.id = 0;
        QCOMPARE(DesktopLaunch::appIndex(QVector<NvApp>{desktop}), -1);
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

    void waitsForNewPairPermissionWithoutChangingDesktopConfig()
    {
        PairingHost host;
        host.launchDenials = 1;
        NvComputer computer = host.computer();
        NvHTTP http(&computer);
        http.m_BaseUrlHttps.setScheme(QStringLiteral("http"));
        STREAM_CONFIGURATION config{};
        config.width = 1920;
        config.height = 1080;
        config.fps = 60;
        config.audioConfiguration = AUDIO_CONFIGURATION_STEREO;
        QString rtsp;
        http.startApp(QStringLiteral("launch"), false, 4321, &config,
                      false, false, 0, false, rtsp, true, true);
        QCOMPARE(host.launchRequests, 2);
        QCOMPARE(rtsp, QStringLiteral("rtsp://127.0.0.1/fixture"));
        const QUrlQuery query(host.lastLaunch);
        QCOMPARE(query.queryItemValue(QStringLiteral("appid")), QStringLiteral("4321"));
        QCOMPARE(query.queryItemValue(QStringLiteral("virtualDisplay")), QStringLiteral("0"));
        QCOMPARE(query.queryItemValue(QStringLiteral("localAudioPlayMode")), QStringLiteral("0"));
    }

    void cancelsPermissionWaitBeforeRetry()
    {
        PairingHost host;
        host.launchDenials = 100;
        NvComputer computer = host.computer();
        NvHTTP http(&computer);
        http.m_BaseUrlHttps.setScheme(QStringLiteral("http"));
        const auto cancelled = std::make_shared<std::atomic_bool>(false);
        http.setCancellation(cancelled);
        STREAM_CONFIGURATION config{};
        QString rtsp;
        QTimer::singleShot(100, this, [cancelled]() { cancelled->store(true); });
        try {
            http.startApp(QStringLiteral("launch"), false, 4321, &config,
                          false, false, 0, false, rtsp, true, true);
            QFAIL("Cancelling must interrupt the permission wait");
        }
        catch (const QtNetworkReplyException& error) {
            QCOMPARE(error.getError(), QNetworkReply::OperationCanceledError);
        }
        QCOMPARE(host.launchRequests, 1);
    }

    void neverRetriesPermissionsForPreviouslyPairedHost()
    {
        PairingHost host;
        host.launchDenials = 100;
        NvComputer computer = host.computer();
        NvHTTP http(&computer);
        http.m_BaseUrlHttps.setScheme(QStringLiteral("http"));
        STREAM_CONFIGURATION config{};
        QString rtsp;
        try {
            http.startApp(QStringLiteral("launch"), false, 4321, &config,
                          false, false, 0, false, rtsp, true);
            QFAIL("An existing host's permission denial must surface immediately");
        }
        catch (const GfeHttpResponseException& error) {
            QCOMPARE(error.getStatusCode(), 403);
        }
        QCOMPARE(host.launchRequests, 1);
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
            {QStringLiteral("MachineManagementView"), QStringLiteral("Your cloud, under your control")},
            {QStringLiteral("MachineManagementView"), QStringLiteral("Start, restart or manage your machines without entering Windows. Each powered-on VM uses its own plan.")},
            {QStringLiteral("MachineManagementView"), QStringLiteral("Machines and storage")},
            {QStringLiteral("MachineManagementView"), QStringLiteral("Shut down")},
            {QStringLiteral("MachineManagementView"), QStringLiteral("Windows disk: %1 GB \u00b7 %2 reinstall credit(s)")},
            {QStringLiteral("MachineManagementView"), QStringLiteral("Transfer this additional disk")},
            {QStringLiteral("MachineManagementView"), QStringLiteral("Accepted operations continue on the server if you close the app. Older upgrades integrated into Windows cannot be transferred separately. Data follows the retention period of its linked plan.")},
            {QStringLiteral("LoadingOverlay"), QStringLiteral("Send files to your VM using Upload. Use a wired connection or 5 GHz Wi-Fi for smoother video.")},
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
        const QString catalog = QStringLiteral(":/languages/qml_") + locale + QStringLiteral(".qm");
        const QByteArray qmlPath = "../gui/" + context.toUtf8() + ".qml";
        QFile qml(QFINDTESTDATA(qmlPath.constData()));
        QVERIFY(qml.open(QIODevice::ReadOnly));
        QString escaped = source;
        escaped.replace(QStringLiteral("\n"), QStringLiteral("\\n"));
        QVERIFY(QString::fromUtf8(qml.readAll()).contains(QStringLiteral("qsTr(\"") + escaped + QStringLiteral("\")")));

        QTranslator translator;
        QVERIFY(translator.load(catalog));
        const QString translated = translator.translate(context.toUtf8().constData(), source.toUtf8().constData());
        QVERIFY(!translated.isEmpty());
        QVERIFY(translated != source);
        QCOMPARE(translated.count(QStringLiteral("%1")), source.count(QStringLiteral("%1")));
        QCOMPARE(translated.count(QStringLiteral("%2")), source.count(QStringLiteral("%2")));
    }
};

QTEST_GUILESS_MAIN(PairingTest)
#include "pairing_test.moc"
