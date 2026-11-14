#include <QtTest>
#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QPointer>
#include <QSettings>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QUrlQuery>
#include <memory>
#include <openssl/pem.h>
#include "../backend/identitymanager.h"
#include "../backend/launcherapi.h"
#include "../backend/streamguestcontroller.h"

class GuestApiFixture
{
public:
    struct Request {
        QByteArray method, authorization;
        QUrl url;
        QJsonObject body;
    };
    QTcpServer server;
    QList<Request> requests;
    QPointer<QTcpSocket> pendingRefresh, pendingGuests, pendingManagement;
    bool holdRefresh = false, holdGuests = false, holdManagement = false;
    QString managedMachine = QStringLiteral("old-vm");
    bool canManage = true;
    std::function<void(QTcpSocket*, const Request&)> streamHandler;

    GuestApiFixture()
    {
        QObject::connect(&server, &QTcpServer::newConnection, &server, [this]() {
            QTcpSocket* socket = server.nextPendingConnection();
            auto bytes = std::make_shared<QByteArray>();
            QObject::connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
            QObject::connect(socket, &QTcpSocket::readyRead, &server, [this, socket, bytes]() {
                if (socket->property("handled").toBool()) return;
                bytes->append(socket->readAll());
                const int end = bytes->indexOf("\r\n\r\n");
                if (end < 0) return;
                int length = 0;
                QByteArray authorization;
                for (const auto& header : bytes->left(end).split('\n')) {
                    if (header.toLower().startsWith("content-length:")) length = header.mid(15).trimmed().toInt();
                    if (header.toLower().startsWith("authorization:")) authorization = header.mid(14).trimmed();
                }
                if (bytes->size() < end + 4 + length) return;
                socket->setProperty("handled", true);
                const auto first = bytes->left(bytes->indexOf("\r\n")).split(' ');
                Request request{first[0], authorization, QUrl::fromEncoded(first[1]),
                                QJsonDocument::fromJson(bytes->mid(end + 4, length)).object()};
                requests.append(request);
                const QString path = request.url.path();
                if (path == QStringLiteral("/auth/login"))
                    write(socket, 200, auth(request.body.value(QStringLiteral("email")).toString()));
                else if (path == QStringLiteral("/auth/refresh") && holdRefresh)
                    pendingRefresh = socket;
                else if (path == QStringLiteral("/auth/refresh"))
                    write(socket, 200, auth(QStringLiteral("refreshed@example.invalid")));
                else if (path == QStringLiteral("/vm-management") && holdManagement)
                    pendingManagement = socket;
                else if (path == QStringLiteral("/vm-management"))
                    write(socket, 200, {{QStringLiteral("machines"), QJsonArray{QJsonObject{
                        {QStringLiteral("id"), managedMachine}}}}});
                else if ((path.startsWith(QStringLiteral("/stream/")) || path == QStringLiteral("/mic/ensure")) && streamHandler)
                    streamHandler(socket, request);
                else if (path == QStringLiteral("/stream/guests") && holdGuests)
                    pendingGuests = socket;
                else if (path == QStringLiteral("/stream/identity/challenge"))
                    write(socket, 200, {{QStringLiteral("challenge"), QStringLiteral("fixture.challenge")}});
                else if (path == QStringLiteral("/stream/identity"))
                    write(socket, 200, {{QStringLiteral("bindingId"), QStringLiteral("owner-binding")},
                                       {QStringLiteral("canManageGuests"), canManage}});
                else if (path == QStringLiteral("/stream/guests"))
                    write(socket, 200, guestList());
                else if (path == QStringLiteral("/status"))
                    write(socket, 200, QJsonObject{{QStringLiteral("state"), QStringLiteral("idle")}});
                else if (path == QStringLiteral("/machines"))
                    write(socket, 200, QJsonObject{{QStringLiteral("machines"), QJsonArray{}}});
                else
                    write(socket, 200, {});
            });
        });
        server.listen(QHostAddress::LocalHost);
    }

    QUrl base() const { return QUrl(QStringLiteral("http://127.0.0.1:%1/").arg(server.serverPort())); }
    int count(const QString& path) const {
        int count = 0;
        for (const auto& request : requests) if (request.url.path() == path) count++;
        return count;
    }
    static QJsonObject auth(const QString& email) {
        return {{QStringLiteral("accessToken"), email},
                {QStringLiteral("refreshToken"), QStringLiteral("fixture-refresh")},
                {QStringLiteral("accessTokenExpiresIn"), 900},
                {QStringLiteral("user"), QJsonObject{{QStringLiteral("email"), email}}}};
    }
    static QJsonObject guestList() {
        return {{QStringLiteral("guests"), QJsonArray{QJsonObject{
            {QStringLiteral("userId"), QStringLiteral("friend-account")},
            {QStringLiteral("displayName"), QStringLiteral("Friend from account")},
            {QStringLiteral("username"), QStringLiteral("friend")},
            {QStringLiteral("devices"), QJsonArray{
                QJsonObject{{QStringLiteral("bindingId"), QStringLiteral("phone-binding")},
                            {QStringLiteral("platform"), QStringLiteral("android")}},
                QJsonObject{{QStringLiteral("bindingId"), QStringLiteral("pc-binding")},
                            {QStringLiteral("platform"), QStringLiteral("windows")}}}}}}},
            {QStringLiteral("unidentifiedCount"), 1}};
    }
    static void write(QTcpSocket* socket, int status, const QJsonObject& response) {
        if (!socket) return;
        const QByteArray body = QJsonDocument(response).toJson(QJsonDocument::Compact);
        socket->write("HTTP/1.1 " + QByteArray::number(status) + " Test\r\nContent-Type: application/json\r\n"
                      "Connection: close\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\n\r\n" + body);
        socket->disconnectFromHost();
    }
};

class StreamGuestsTest : public QObject
{
    Q_OBJECT
    QTemporaryDir settings;

    void login(LauncherApi& api, const QString& email = QStringLiteral("owner@example.invalid")) {
        api.setRememberMe(false);
        api.login(email, QStringLiteral("fixture-only"));
        QTRY_VERIFY(api.loggedIn() && !api.busy());
        QCOMPARE(api.email(), email);
    }

private slots:
    void initTestCase() {
        QVERIFY(settings.isValid());
        QCoreApplication::setOrganizationName(QStringLiteral("Space Connect Tests"));
        QCoreApplication::setApplicationName(QStringLiteral("StreamGuests"));
        QSettings::setDefaultFormat(QSettings::IniFormat);
        QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, settings.path());
        IdentityManager::get();
    }
    void init() { QSettings().clear(); }

    void microphoneWaitsForInstallationAndKeepsItsOriginalMachine()
    {
        GuestApiFixture host;
        LauncherApi api(nullptr, host.base());
        login(api);
        host.streamHandler = [&host](QTcpSocket* socket, const GuestApiFixture::Request&) {
            const bool ready = host.count(QStringLiteral("/mic/ensure")) > 1;
            GuestApiFixture::write(socket, 200, {{QStringLiteral("ok"), ready},
                {QStringLiteral("state"), ready ? QStringLiteral("ready") : QStringLiteral("installing")},
                {QStringLiteral("protocol"), 2}});
        };
        QObject receiver;
        int delivered = 0;
        QString state;
        api.ensureMicBridge(QStringLiteral("original-vm"), api.accountGeneration(), receiver,
            [&](int status, const QJsonObject& response) {
                QCOMPARE(status, 200);
                delivered++;
                state = response.value(QStringLiteral("state")).toString();
            });
        api.setSelectedMachineId(QStringLiteral("different-vm"));
        QTRY_COMPARE_WITH_TIMEOUT(state, QStringLiteral("ready"), 5000);
        QCOMPARE(delivered, 1);
        QCOMPARE(host.count(QStringLiteral("/mic/ensure")), 2);
        for (const auto& request : host.requests) {
            if (request.url.path() != QStringLiteral("/mic/ensure")) continue;
            QCOMPARE(request.body.value(QStringLiteral("machineId")).toString(), QStringLiteral("original-vm"));
            QCOMPARE(request.body.value(QStringLiteral("protocol")).toInt(), 2);
        }
    }

    void leavingTheStreamCancelsPendingMicrophoneInstallation()
    {
        GuestApiFixture host;
        LauncherApi api(nullptr, host.base());
        login(api);
        host.streamHandler = [](QTcpSocket* socket, const GuestApiFixture::Request&) {
            GuestApiFixture::write(socket, 200, {{QStringLiteral("ok"), false},
                                               {QStringLiteral("state"), QStringLiteral("installing")}});
        };
        auto* receiver = new QObject;
        api.ensureMicBridge(QStringLiteral("original-vm"), api.accountGeneration(), *receiver,
                            [](int, const QJsonObject&) {});
        QTRY_COMPARE(host.count(QStringLiteral("/mic/ensure")), 1);
        QTest::qWait(50);
        delete receiver;
        QTest::qWait(2100);
        QCOMPARE(host.count(QStringLiteral("/mic/ensure")), 1);
    }

    void proofUsesTheExistingPairedCertificateAndProtocolDomain() {
        auto* identity = IdentityManager::get();
        const QByteArray certificate = identity->getCertificate();
        const QByteArray signature = QByteArray::fromBase64(identity->signStreamChallenge(QStringLiteral("fixture.challenge")));
        std::unique_ptr<BIO, decltype(&BIO_free)> bio(BIO_new_mem_buf(certificate.constData(), certificate.size()), BIO_free);
        std::unique_ptr<X509, decltype(&X509_free)> cert(PEM_read_bio_X509(bio.get(), nullptr, nullptr, nullptr), X509_free);
        QVERIFY(cert);
        std::unique_ptr<EVP_PKEY, decltype(&EVP_PKEY_free)> key(X509_get_pubkey(cert.get()), EVP_PKEY_free);
        std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)> context(EVP_MD_CTX_new(), EVP_MD_CTX_free);
        const QByteArray payload("SpaceConnect stream identity v1\nfixture.challenge");
        QCOMPARE(EVP_DigestVerifyInit(context.get(), nullptr, EVP_sha256(), nullptr, key.get()), 1);
        QCOMPARE(EVP_DigestVerifyUpdate(context.get(), payload.constData(), payload.size()), 1);
        QCOMPARE(EVP_DigestVerifyFinal(context.get(),
                    reinterpret_cast<const unsigned char*>(signature.constData()), signature.size()), 1);
        QCOMPARE(identity->getCertificate(), certificate);
    }

    void anInPlaceLoginInvalidatesThePreviousStreamAccount() {
        GuestApiFixture host;
        LauncherApi api(nullptr, host.base());
        login(api);
        const quint64 original = api.accountGeneration();
        login(api, QStringLiteral("other@example.invalid"));
        QVERIFY(api.accountGeneration() != original);
    }

    void aLateRememberedRefreshCannotUndoLogout() {
        QSettings saved;
        saved.setValue(QStringLiteral("auth/email"), QStringLiteral("saved@example.invalid"));
        saved.setValue(QStringLiteral("auth/refreshToken"), QStringLiteral("fixture-refresh"));
        saved.setValue(QStringLiteral("auth/savedAt"), QDateTime::currentMSecsSinceEpoch());
        GuestApiFixture host;
        host.holdRefresh = true;
        LauncherApi api(nullptr, host.base());
        QTRY_VERIFY(host.pendingRefresh);
        api.logout();
        QSignalSpy loggedIn(&api, &LauncherApi::loggedInChanged);
        GuestApiFixture::write(host.pendingRefresh, 200, GuestApiFixture::auth(QStringLiteral("old@example.invalid")));
        QVERIFY(!loggedIn.wait(150));
        QVERIFY(!api.loggedIn());
        QVERIFY(QSettings().value(QStringLiteral("auth/refreshToken")).toString().isEmpty());
    }

    void obsoleteStreamRequestsDoNotUseTheNewAccountsToken() {
        GuestApiFixture host;
        LauncherApi api(nullptr, host.base());
        login(api);
        const quint64 original = api.accountGeneration();
        api.logout();
        login(api, QStringLiteral("other@example.invalid"));
        QString error;
        api.streamRequest(original, "GET", QStringLiteral("stream/guests?machineId=original"), {}, *this,
                          [&error](int, const QJsonObject& root) {
            error = root.value(QStringLiteral("error")).toObject().value(QStringLiteral("code")).toString();
        });
        QTRY_COMPARE(error, QStringLiteral("ACCOUNT_CHANGED"));
        QCOMPARE(host.count(QStringLiteral("/stream/guests")), 0);
    }

    void anInPlaceLoginReplacesManagementEvenWhenThePreviousReadIsPending() {
        GuestApiFixture host;
        LauncherApi api(nullptr, host.base());
        login(api);
        QTRY_COMPARE(api.management().value(QStringLiteral("machines")).toList().size(), 1);
        host.holdManagement = true;
        api.refreshManagement();
        QTRY_VERIFY(host.pendingManagement);
        host.holdManagement = false;
        host.managedMachine = QStringLiteral("new-vm");
        login(api, QStringLiteral("other@example.invalid"));
        QTRY_COMPARE(api.management().value(QStringLiteral("machines")).toList().value(0).toMap()
                     .value(QStringLiteral("id")).toString(), QStringLiteral("new-vm"));
        GuestApiFixture::write(host.pendingManagement, 200, {{QStringLiteral("machines"), QJsonArray{}}});
        QTest::qWait(25);
        QCOMPARE(api.management().value(QStringLiteral("machines")).toList().size(), 1);
    }

    void aLateExpiredRefreshCannotLogOutANewerAccount() {
        QSettings saved;
        saved.setValue(QStringLiteral("auth/email"), QStringLiteral("saved@example.invalid"));
        saved.setValue(QStringLiteral("auth/refreshToken"), QStringLiteral("fixture-refresh"));
        saved.setValue(QStringLiteral("auth/savedAt"), QDateTime::currentMSecsSinceEpoch());
        GuestApiFixture host;
        host.holdRefresh = true;
        LauncherApi api(nullptr, host.base());
        QTRY_VERIFY(host.pendingRefresh);
        login(api, QStringLiteral("new@example.invalid"));
        const quint64 account = api.accountGeneration();
        QSignalSpy changed(&api, &LauncherApi::loggedInChanged);
        GuestApiFixture::write(host.pendingRefresh, 401, {});
        QVERIFY(!changed.wait(100));
        QVERIFY(api.loggedIn());
        QCOMPARE(api.accountGeneration(), account);
        QCOMPARE(api.email(), QStringLiteral("new@example.invalid"));
    }

    void destroyingARequestScopeAbortsOnlyItsReply() {
        GuestApiFixture host;
        host.holdGuests = true;
        LauncherApi api(nullptr, host.base());
        login(api);
        QObject* scope = new QObject(this);
        int callbacks = 0;
        QNetworkReply* reply = api.streamRequest(api.accountGeneration(), "GET", QStringLiteral("stream/guests"),
                                                {}, *scope, [&callbacks](int, const QJsonObject&) { callbacks++; });
        QVERIFY(reply);
        QSignalSpy finished(reply, &QNetworkReply::finished);
        QTRY_VERIFY(host.pendingGuests);
        QCOMPARE(host.requests.last().authorization, QByteArray("Bearer owner@example.invalid"));
        delete scope;
        QTRY_COMPARE(finished.count(), 1);
        QCOMPARE(callbacks, 0);
        QVERIFY(api.loggedIn());
    }

    void guestsAreRegisteredWithThePairedIdentityAndFrozenMachine() {
        GuestApiFixture host;
        LauncherApi api(nullptr, host.base());
        login(api);
        StreamGuestController controller(&api, QStringLiteral("original-vm"), api.accountGeneration(), true);
        api.setSelectedMachineId(QStringLiteral("another-vm"));
        controller.start();
        controller.setPanelOpen(true);
        QTRY_VERIFY_WITH_TIMEOUT(controller.loaded(), 1000);
        QCOMPARE(controller.guests().size(), 1);
        QCOMPARE(controller.guests().first().toMap().value(QStringLiteral("displayName")).toString(),
                 QStringLiteral("Friend from account"));
        QCOMPARE(controller.guests().first().toMap().value(QStringLiteral("devices")).toList().size(), 2);
        QCOMPARE(controller.unidentifiedCount(), 1);
        for (const auto& request : host.requests) {
            if (request.url.path() == QStringLiteral("/stream/identity")) {
                QCOMPARE(request.body.value(QStringLiteral("machineId")).toString(), QStringLiteral("original-vm"));
                QCOMPARE(request.body.value(QStringLiteral("certificatePem")).toString().toUtf8(),
                         IdentityManager::get()->getCertificate());
                QCOMPARE(request.body.value(QStringLiteral("challenge")).toString(), QStringLiteral("fixture.challenge"));
                QVERIFY(!QByteArray::fromBase64(request.body.value(QStringLiteral("signature")).toString().toLatin1()).isEmpty());
            }
            if (request.url.path() == QStringLiteral("/stream/guests")) {
                QCOMPARE(QUrlQuery(request.url).queryItemValue(QStringLiteral("machineId")), QStringLiteral("original-vm"));
                QCOMPARE(QUrlQuery(request.url).queryItemValue(QStringLiteral("bindingId")), QStringLiteral("owner-binding"));
            }
        }
        QCOMPARE(host.count(QStringLiteral("/stream/identity")), 1);
    }

    void friendsRegisterButCannotOpenOwnerControls() {
        GuestApiFixture host;
        host.canManage = false;
        LauncherApi api(nullptr, host.base());
        login(api);
        StreamGuestController controller(&api, QStringLiteral("friend-vm"), api.accountGeneration(), false);
        controller.start();
        QTRY_COMPARE_WITH_TIMEOUT(host.count(QStringLiteral("/stream/identity")), 1, 1000);
        QTRY_VERIFY(!controller.busy());
        controller.setPanelOpen(true);
        controller.requestDisconnect(QStringLiteral("friend-account"));
        controller.confirmDisconnect();
        QVERIFY(!controller.ownerSession());
        QVERIFY(!controller.panelOpen());
        QCOMPARE(host.count(QStringLiteral("/stream/guests")), 0);
        QCOMPARE(host.count(QStringLiteral("/stream/guests/disconnect")), 0);
    }

    void closingThePanelCancelsTheListWithoutKeepingOldGuests() {
        GuestApiFixture host;
        host.holdGuests = true;
        LauncherApi api(nullptr, host.base());
        login(api);
        StreamGuestController controller(&api, QStringLiteral("vm"), api.accountGeneration(), true);
        controller.start();
        controller.setPanelOpen(true);
        QTRY_VERIFY_WITH_TIMEOUT(host.pendingGuests, 1000);
        controller.setPanelOpen(false);
        QVERIFY(!controller.busy());
        QVERIFY(controller.guests().isEmpty());
        QVERIFY(!controller.loaded());
        host.holdGuests = false;
        controller.setPanelOpen(true);
        QTRY_VERIFY_WITH_TIMEOUT(controller.loaded(), 1000);
        QCOMPARE(host.count(QStringLiteral("/stream/guests")), 2);
    }

    void closingFromABusyListenerCannotUseADestroyedRequestScope() {
        GuestApiFixture host;
        LauncherApi api(nullptr, host.base());
        login(api);
        StreamGuestController controller(&api, QStringLiteral("vm"), api.accountGeneration(), true);
        controller.start();
        QTRY_COMPARE(host.count(QStringLiteral("/stream/identity")), 1);
        QTRY_VERIFY(!controller.busy());
        connect(&controller, &StreamGuestController::changed, &controller, [&controller]() {
            if (controller.panelOpen() && controller.busy()) controller.setPanelOpen(false);
        });
        controller.setPanelOpen(true);
        QVERIFY(!controller.panelOpen());
        QVERIFY(!controller.busy());
        QVERIFY(controller.guests().isEmpty());
    }

    void aConfirmedDisconnectIsNotRepeatedWhenClosingOrReopening() {
        GuestApiFixture host;
        LauncherApi api(nullptr, host.base());
        login(api);
        StreamGuestController controller(&api, QStringLiteral("vm"), api.accountGeneration(), true);
        controller.start();
        controller.setPanelOpen(true);
        QTRY_VERIFY_WITH_TIMEOUT(controller.loaded(), 1000);
        controller.confirmDisconnect();
        QCOMPARE(host.count(QStringLiteral("/stream/guests/disconnect")), 0);
        controller.requestDisconnect(QStringLiteral("friend-account"));
        QCOMPARE(controller.confirmationName(), QStringLiteral("Friend from account"));
        QPointer<QTcpSocket> pending;
        host.streamHandler = [&pending](QTcpSocket* socket, const GuestApiFixture::Request& request) {
            if (request.url.path() == QStringLiteral("/stream/guests/disconnect"))
                pending = socket;
            else
                GuestApiFixture::write(socket, 200, GuestApiFixture::guestList());
        };
        controller.confirmDisconnect();
        QTRY_VERIFY_WITH_TIMEOUT(pending, 1000);
        QCOMPARE(host.requests.last().body.value(QStringLiteral("guestUserId")).toString(), QStringLiteral("friend-account"));
        controller.setPanelOpen(false);
        QVERIFY(controller.busy());
        controller.setPanelOpen(true);
        controller.confirmDisconnect();
        GuestApiFixture::write(pending, 200, {{QStringLiteral("requested"), 2}, {QStringLiteral("alreadyDisconnected"), 0}});
        QTRY_VERIFY(!controller.busy());
        QVERIFY(!controller.notice().isEmpty());
        QCOMPARE(host.count(QStringLiteral("/stream/guests/disconnect")), 1);
    }

    void disconnectFailurePreservesPartialCountsWithoutRetry() {
        GuestApiFixture host;
        LauncherApi api(nullptr, host.base());
        login(api);
        StreamGuestController controller(&api, QStringLiteral("vm"), api.accountGeneration(), true);
        controller.setPanelOpen(true);
        QTRY_VERIFY_WITH_TIMEOUT(controller.loaded(), 1000);
        host.streamHandler = [](QTcpSocket* socket, const GuestApiFixture::Request&) {
            GuestApiFixture::write(socket, 503, {{QStringLiteral("error"), QJsonObject{
                {QStringLiteral("code"), QStringLiteral("STREAM_HOST_UNAVAILABLE")},
                {QStringLiteral("requested"), 1}, {QStringLiteral("alreadyDisconnected"), 0},
                {QStringLiteral("failed"), 1}, {QStringLiteral("notAttempted"), 0}}}});
        };
        controller.requestDisconnect(QStringLiteral("friend-account"));
        controller.confirmDisconnect();
        QTRY_VERIFY(!controller.busy());
        QVERIFY(!controller.errorMessage().isEmpty());
        QCOMPARE(controller.partialResult().value(QStringLiteral("requested")).toInt(), 1);
        QCOMPARE(controller.partialResult().value(QStringLiteral("failed")).toInt(), 1);
        QVERIFY(controller.guests().isEmpty());
        QCOMPARE(host.count(QStringLiteral("/stream/guests/disconnect")), 1);
    }

    void changingAccountsInvalidatesAConfirmation() {
        GuestApiFixture host;
        LauncherApi api(nullptr, host.base());
        login(api);
        StreamGuestController controller(&api, QStringLiteral("vm"), api.accountGeneration(), true);
        controller.setPanelOpen(true);
        QTRY_VERIFY_WITH_TIMEOUT(controller.loaded(), 1000);
        controller.requestDisconnect(QStringLiteral("friend-account"));
        api.logout();
        login(api, QStringLiteral("other@example.invalid"));
        controller.confirmDisconnect();
        QVERIFY(controller.confirmationName().isEmpty());
        QVERIFY(controller.guests().isEmpty());
        QVERIFY(!controller.ownerSession());
        QCOMPARE(host.count(QStringLiteral("/stream/guests/disconnect")), 0);
    }
};

QTEST_MAIN(StreamGuestsTest)
#include "stream_guests_test.moc"
