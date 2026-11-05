#include <QtTest>
#include <QJsonArray>
#include <QPointer>
#include <QSettings>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QUrlQuery>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQmlExpression>
#include <QQmlIncubationController>
#include <QQuickWindow>
#include <QScopeGuard>
#include <memory>

#include "../backend/computermanager.h"
#include "../backend/autoupdatechecker.h"
#include "../backend/latencytester.h"
#include "../backend/launcherapi.h"
#include "../backend/micleveltester.h"
#include "../backend/sessionorchestrator.h"
#include "../backend/streamguestcontroller.h"
#include "../backend/systemproperties.h"
#include "../backend/xdapi.h"
#include "../gui/appmodel.h"
#include "../gui/computermodel.h"
#include "../gui/sdlgamepadkeynavigation.h"
#include "../streaming/session.h"
#include "../streaming/input/inputtimer.h"
#include "../settings/streamingpreferences.h"

class LauncherFixture
{
public:
    struct Request {
        QByteArray method;
        QUrl url;
        QJsonObject body;
        QByteArray authorization;
    };

    QTcpServer server;
    QList<Request> requests;
    QString state = QStringLiteral("idle");
    bool holdJoin = false;
    bool holdConnection = true;
    bool holdStatus = false;
    int connectionStatus = 503;
    QJsonObject connectionError;
    int statusFailures = 0;
    int statusFailureCode = 503;
    QJsonObject statusError;
    QList<QPair<int, QJsonObject>> statusSequence;
    int joinStatus = 200;
    QJsonObject joinError;
    QPointer<QTcpSocket> pendingJoin;
    QPointer<QTcpSocket> pendingConnection;
    QPointer<QTcpSocket> pendingStatus;
    QJsonObject pendingStatusBody;
    QHash<QString, QString> machineStates;
    QHash<QString, QJsonObject> statusOverrides;
    QSet<QString> heldMachines;
    QHash<QString, QPointer<QTcpSocket>> pendingMachineStatus;
    QHash<QString, QJsonObject> pendingMachineBody;
    QJsonObject management{{QStringLiteral("machines"), QJsonArray()},
                           {QStringLiteral("operations"), QJsonArray()}};

    LauncherFixture()
    {
        QObject::connect(&server, &QTcpServer::newConnection, &server, [this]() {
            QTcpSocket* socket = server.nextPendingConnection();
            auto input = std::make_shared<QByteArray>();
            QObject::connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
            QObject::connect(socket, &QTcpSocket::readyRead, &server, [this, socket, input]() {
                input->append(socket->readAll());
                const int headerEnd = input->indexOf("\r\n\r\n");
                if (headerEnd < 0)
                    return;
                int length = 0;
                QByteArray authorization;
                for (const QByteArray& header : input->left(headerEnd).split('\n')) {
                    if (header.toLower().startsWith("content-length:"))
                        length = header.mid(15).trimmed().toInt();
                    if (header.toLower().startsWith("authorization:"))
                        authorization = header.mid(14).trimmed();
                }
                if (input->size() < headerEnd + 4 + length)
                    return;
                const auto line = input->left(input->indexOf("\r\n")).split(' ');
                const Request request{line[0], QUrl::fromEncoded(line[1]),
                    QJsonDocument::fromJson(input->mid(headerEnd + 4, length)).object(), authorization};
                requests.append(request);
                respond(socket, request);
            });
        });
        server.listen(QHostAddress::LocalHost);
    }

    QUrl base() const
    {
        return QUrl(QStringLiteral("http://127.0.0.1:%1/").arg(server.serverPort()));
    }

    int count(const QByteArray& method, const QString& path) const
    {
        int result = 0;
        for (const auto& request : requests) {
            if (request.method == method && request.url.path() == path)
                result++;
        }
        return result;
    }

    Request last(const QString& path) const
    {
        for (auto it = requests.crbegin(); it != requests.crend(); ++it) {
            if (it->url.path() == path)
                return *it;
        }
        return {};
    }

    QJsonObject status(const QString& machineId = QStringLiteral("chosen")) const
    {
        if (statusOverrides.contains(machineId)) return statusOverrides.value(machineId);
        const QString current = machineStates.value(machineId, state);
        QJsonObject result{{QStringLiteral("state"), current}};
        if (current == QStringLiteral("queued")) {
            result.insert(QStringLiteral("queue"), QJsonObject{
                {QStringLiteral("machineId"), machineId},
                {QStringLiteral("position"), 2}, {QStringLiteral("total"), 4}});
            result.insert(QStringLiteral("bootTexts"), QJsonObject{
                {QStringLiteral("queueTitle"), QStringLiteral("Fila {position} de {total}")}});
        }
        if (current == QStringLiteral("starting") || current == QStringLiteral("ready")) {
            result.insert(QStringLiteral("session"), QJsonObject{
                {QStringLiteral("id"), QStringLiteral("session-") + machineId},
                {QStringLiteral("machine"), QJsonObject{
                    {QStringLiteral("id"), machineId},
                    {QStringLiteral("name"), machineId},
                    {QStringLiteral("creationPhase"), current == QStringLiteral("ready")
                        ? QString() : QStringLiteral("booting")},
                }},
            });
        }
        return result;
    }

    void finishJoin(int code)
    {
        // Even a lost/error response can follow a committed queue insertion.
        state = QStringLiteral("queued");
        write(pendingJoin, code, code == 200 ? status() : QJsonObject());
        pendingJoin.clear();
    }

    void finishStatus()
    {
        write(pendingStatus, 200, pendingStatusBody);
        pendingStatus.clear();
    }

    void finishMachineStatus(const QString& machineId)
    {
        write(pendingMachineStatus.take(machineId), 200, pendingMachineBody.take(machineId));
    }

private:
    static void write(QTcpSocket* socket, int code, const QJsonObject& response)
    {
        if (!socket)
            return;
        const QByteArray body = QJsonDocument(response).toJson(QJsonDocument::Compact);
        socket->write("HTTP/1.1 " + QByteArray::number(code) + " Test\r\n"
                      "Content-Type: application/json\r\nConnection: close\r\nContent-Length: "
                      + QByteArray::number(body.size()) + "\r\n\r\n" + body);
        socket->disconnectFromHost();
    }

    void respond(QTcpSocket* socket, const Request& request)
    {
        const QString path = request.url.path();
        if (path == QStringLiteral("/health")) {
            write(socket, 200, {});
        }
        else if (path == QStringLiteral("/auth/login")) {
            write(socket, 200, QJsonObject{
                {QStringLiteral("accessToken"), request.body.value(QStringLiteral("email")).toString() == QStringLiteral("other@example.invalid")
                    ? QStringLiteral("other-access") : QStringLiteral("fixture-access")},
                {QStringLiteral("refreshToken"), QStringLiteral("fixture-refresh")},
                {QStringLiteral("accessTokenExpiresIn"), 900},
                {QStringLiteral("user"), QJsonObject{{QStringLiteral("email"), request.body.value(QStringLiteral("email"))}}},
            });
        }
        else if (path == QStringLiteral("/machines")) {
            write(socket, 200, QJsonObject{
                {QStringLiteral("machines"), QJsonArray{
                    QJsonObject{{QStringLiteral("id"), QStringLiteral("chosen")}},
                    QJsonObject{{QStringLiteral("id"), QStringLiteral("other")}},
                }},
            });
        }
        else if (path == QStringLiteral("/status")) {
            if (!statusSequence.isEmpty()) {
                const auto response = statusSequence.takeFirst();
                write(socket, response.first, response.second);
                return;
            }
            if (statusFailures > 0) {
                --statusFailures;
                write(socket, statusFailureCode, statusError);
                return;
            }
            const QString machineId = QUrlQuery(request.url).queryItemValue(QStringLiteral("machineId"));
            const QJsonObject body = status(machineId);
            if (heldMachines.contains(machineId)) {
                pendingMachineStatus.insert(machineId, socket);
                pendingMachineBody.insert(machineId, body);
            }
            else if (holdStatus) { pendingStatus = socket; pendingStatusBody = body; }
            else write(socket, 200, body);
        }
        else if (path == QStringLiteral("/vm-management")) {
            write(socket, 200, management);
        }
        else if (path == QStringLiteral("/queue") && request.method == "POST") {
            if (joinStatus != 200) {
                write(socket, joinStatus, joinError);
                return;
            }
            pendingJoin = socket;
            if (!holdJoin)
                finishJoin(200);
        }
        else if (path == QStringLiteral("/queue") && request.method == "DELETE") {
            state = QStringLiteral("idle");
            write(socket, 200, status());
        }
        else if (path == QStringLiteral("/connection")) {
            if (holdConnection)
                pendingConnection = socket;
            else
                write(socket, connectionStatus, connectionError);
        }
        else if (path == QStringLiteral("/pair")) {
            write(socket, 503, {});
        }
        else if (path == QStringLiteral("/stream/identity/challenge")) {
            write(socket, 200, {{QStringLiteral("challenge"), QStringLiteral("fixture.challenge")}});
        }
        else if (path == QStringLiteral("/stream/identity")) {
            write(socket, 200, {{QStringLiteral("bindingId"), QStringLiteral("binding")},
                               {QStringLiteral("canManageGuests"), true}});
        }
        else {
            write(socket, 404, {});
        }
    }
};

class SessionOrchestratorTest : public QObject
{
    Q_OBJECT
    QTemporaryDir m_Settings;
    std::unique_ptr<LauncherFixture> m_Host;
    std::unique_ptr<LauncherApi> m_Api;
    std::unique_ptr<ComputerManager> m_Computers;
    std::unique_ptr<SessionOrchestrator> m_Orchestrator;

    void publishManagementSession(const QString& sessionId = QStringLiteral("session-chosen"))
    {
        m_Host->management.insert(QStringLiteral("machines"), QJsonArray{QJsonObject{
            {QStringLiteral("id"), QStringLiteral("chosen")},
            {QStringLiteral("name"), QStringLiteral("chosen")},
            {QStringLiteral("session"), QJsonObject{{QStringLiteral("id"), sessionId}}},
            {QStringLiteral("phases"), QJsonObject()},
        }});
    }

private slots:
    void expiredPlanUsesItsBusinessCodeWithoutRetryingTheJoin()
    {
        QSignalSpy failure(m_Orchestrator.get(), &SessionOrchestrator::errorOccurred);
        m_Host->joinStatus = 402;
        m_Host->joinError = {{QStringLiteral("error"), QJsonObject{
            {QStringLiteral("code"), QStringLiteral("PLAN_EXPIRED")},
            {QStringLiteral("message"), QStringLiteral("upstream business error")}}}};
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(failure.count(), 1);
        QCOMPARE(failure.first().first().toString(),
                 QStringLiteral("Your plan has expired. Renew it before starting this machine."));
        QVERIFY(!m_Orchestrator->active());
        QCOMPARE(m_Host->count("POST", QStringLiteral("/queue")), 1);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
    }

    void aTransientStatusReadRecoversWithoutAnotherQueuePost()
    {
        QSignalSpy failure(m_Orchestrator.get(), &SessionOrchestrator::errorOccurred);
        m_Host->state = QStringLiteral("queued");
        m_Host->statusFailures = 1;
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(m_Host->statusFailures, 0);
        QTRY_VERIFY(!m_Api->busy());
        QVERIFY(m_Orchestrator->active());
        QVERIFY(!m_Orchestrator->statusText().isEmpty());
        QTRY_COMPARE_WITH_TIMEOUT(m_Orchestrator->phase(), QStringLiteral("queued"), 4500);
        QCOMPARE(failure.count(), 0);
        QCOMPARE(m_Host->count("POST", QStringLiteral("/queue")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
    }

    void statusReadRecoveryStopsAfterTwoRetries()
    {
        QSignalSpy failure(m_Orchestrator.get(), &SessionOrchestrator::errorOccurred);
        const int before = m_Host->count("GET", QStringLiteral("/status"));
        m_Host->statusFailures = 8;
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE_WITH_TIMEOUT(failure.count(), 1, 8500);
        QVERIFY(!m_Orchestrator->active());
        QCOMPARE(m_Host->count("GET", QStringLiteral("/status")) - before, 3);
        QCOMPARE(m_Host->count("POST", QStringLiteral("/queue")), 0);
    }

    void anUnconfirmedQueuePostIsNotRetried()
    {
        QSignalSpy failure(m_Orchestrator.get(), &SessionOrchestrator::errorOccurred);
        m_Host->joinStatus = 503;
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(failure.count(), 1);
        QVERIFY(!m_Orchestrator->active());
        QCOMPARE(m_Host->count("POST", QStringLiteral("/queue")), 1);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
    }

    void businessStatusErrorsNeverRetry_data()
    {
        QTest::addColumn<int>("status");
        QTest::addColumn<QString>("code");
        QTest::newRow("expired") << 402 << QStringLiteral("PLAN_EXPIRED");
        QTest::newRow("busy") << 409 << QStringLiteral("MACHINE_OPERATION_IN_PROGRESS");
        QTest::newRow("unknown-server-code") << 503 << QStringLiteral("PROVIDER_OPERATION_FAILED");
        QTest::newRow("tls-failure") << 503 << QStringLiteral("CLIENT_TLS_ERROR");
    }

    void businessStatusErrorsNeverRetry()
    {
        QFETCH(int, status);
        QFETCH(QString, code);
        QSignalSpy failure(m_Orchestrator.get(), &SessionOrchestrator::errorOccurred);
        const int before = m_Host->count("GET", QStringLiteral("/status"));
        m_Host->statusFailures = 8;
        m_Host->statusFailureCode = status;
        m_Host->statusError = {{QStringLiteral("error"), QJsonObject{{QStringLiteral("code"), code}}}};
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(failure.count(), 1);
        QVERIFY(!m_Orchestrator->active());
        QCOMPARE(m_Host->count("GET", QStringLiteral("/status")) - before, 1);
        QCOMPARE(m_Host->count("POST", QStringLiteral("/queue")), 0);
    }

    void touchTimersQueueTheirEffectForTheInputThreadAndBecomeInvalidWhenCancelled()
    {
        QCOMPARE(SDL_InitSubSystem(SDL_INIT_TIMER | SDL_INIT_EVENTS), 0);
        struct Cleanup {
            ~Cleanup() { SDL_QuitSubSystem(SDL_INIT_TIMER | SDL_INIT_EVENTS); }
        } cleanup;
        const auto waitTimer = [](SDL_Event& event) {
            QElapsedTimer deadline;
            deadline.start();
            while (deadline.elapsed() < 1000) {
                if (SDL_PeepEvents(&event, 1, SDL_GETEVENT, SDL_USEREVENT, SDL_USEREVENT) == 1)
                    return true;
                QTest::qWait(1);
            }
            return false;
        };
        InputTimer timer;
        SDL_Event event{};
        timer.start(5);
        QVERIFY(waitTimer(event));
        QVERIFY(timer.accept(event));
        QVERIFY(!timer.accept(event));
        timer.start(5);
        QVERIFY(waitTimer(event));
        timer.cancel();
        QVERIFY(!timer.accept(event));
        InputTimer nextSession;
        nextSession.start(5);
        QVERIFY(!nextSession.accept(event));
        QVERIFY(waitTimer(event));
        QVERIFY(nextSession.accept(event));
    }

    void initTestCase()
    {
        qRegisterMetaType<Session*>("Session*");
        QVERIFY(m_Settings.isValid());
        QCoreApplication::setOrganizationName(QStringLiteral("Space Connect Tests"));
        QCoreApplication::setApplicationName(QStringLiteral("Session"));
        QSettings::setDefaultFormat(QSettings::IniFormat);
        QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, m_Settings.path());
    }

    void init()
    {
        QSettings().clear();
        m_Host.reset(new LauncherFixture());
        QVERIFY(m_Host->server.isListening());
        m_Api.reset(new LauncherApi(nullptr, m_Host->base()));
        m_Api->setRememberMe(false);
        m_Api->setSelectedMachineId(QStringLiteral("chosen"));
        m_Api->login(QStringLiteral("fixture@example.invalid"), QStringLiteral("fixture-only"));
        QTRY_VERIFY(m_Api->loggedIn() && !m_Api->busy() && m_Api->machinesLoaded());
        m_Computers.reset(new ComputerManager(StreamingPreferences::get()));
        m_Orchestrator.reset(new SessionOrchestrator());
        m_Orchestrator->initialize(m_Api.get(), m_Computers.get());
    }

    void cleanup()
    {
        m_Orchestrator.reset();
        m_Computers.reset();
        m_Api.reset();
        m_Host.reset();
    }

    void queueBootReadyConnectsOnceToFrozenMachine()
    {
        QSignalSpy boot(m_Orchestrator.get(), &SessionOrchestrator::machineBooting);
        QSignalSpy error(m_Orchestrator.get(), &SessionOrchestrator::errorOccurred);
        QSignalSpy connection(m_Api.get(), &LauncherApi::connectionReady);
        m_Host->state = QStringLiteral("queued");
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(m_Orchestrator->phase(), QStringLiteral("queued"));
        QCOMPARE(m_Orchestrator->statusText(), QStringLiteral("Fila 2 de 4"));
        m_Api->setSelectedMachineId(QStringLiteral("other"));
        QTRY_VERIFY(!m_Api->busy());
        m_Host->state = QStringLiteral("starting");
        m_Api->refreshStatus(QStringLiteral("chosen"));
        QTRY_COMPARE(m_Orchestrator->phase(), QStringLiteral("booting"));
        m_Api->statusChanged();
        QCOMPARE(boot.count(), 1);
        m_Host->state = QStringLiteral("ready");
        m_Api->refreshStatus(QStringLiteral("chosen"));
        QTRY_COMPARE(m_Host->count("GET", QStringLiteral("/connection")), 1);
        m_Orchestrator->startDesktopSession();
        m_Api->statusChanged();
        QTest::qWait(100);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 1);
        QCOMPARE(QUrlQuery(m_Host->last(QStringLiteral("/connection")).url)
                 .queryItemValue(QStringLiteral("machineId")), QStringLiteral("chosen"));
        m_Orchestrator->cancel();
        QTRY_VERIFY(m_Host->pendingConnection.isNull());
        QVERIFY(!m_Orchestrator->busy());
        QCOMPARE(connection.count(), 0);
        QCOMPARE(error.count(), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/session")), 0);
    }

    void backgroundHandoffPreservesTheQueueAndWaitsForManualResume()
    {
        QSignalSpy notice(m_Api.get(), &LauncherApi::backgroundNotice);
        QSignalSpy ready(m_Orchestrator.get(), &SessionOrchestrator::sessionReady);
        m_Host->state = QStringLiteral("queued");
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(m_Orchestrator->phase(), QStringLiteral("queued"));
        QTRY_VERIFY(m_Orchestrator->property("canBackground").toBool());
        bool handedOff = false;
        QVERIFY(QMetaObject::invokeMethod(m_Orchestrator.get(), "followInBackground", Q_RETURN_ARG(bool, handedOff)));
        QVERIFY(handedOff);
        QVERIFY(!m_Orchestrator->active() && !m_Orchestrator->busy());
        QVERIFY(m_Orchestrator->property("backgrounded").toBool());
        m_Orchestrator->cancel();
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
        m_Host->state = QStringLiteral("ready");
        m_Api->refreshManagement();
        QTRY_COMPARE_WITH_TIMEOUT(notice.count(), 1, 1500);
        QVERIFY(notice.first().first().toString().contains(QStringLiteral("chosen")));
        m_Api->refreshManagement();
        QTest::qWait(100);
        QCOMPARE(notice.count(), 1);
        QCOMPARE(ready.count(), 0);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
        m_Api->setSelectedMachineId(QStringLiteral("other"));
        QTRY_VERIFY(!m_Api->busy());
        QVERIFY(QMetaObject::invokeMethod(m_Orchestrator.get(), "resumeConnection"));
        QTRY_COMPARE(m_Host->count("GET", QStringLiteral("/connection")), 1);
        QCOMPARE(QUrlQuery(m_Host->last(QStringLiteral("/connection")).url).queryItemValue(QStringLiteral("machineId")),
                 QStringLiteral("chosen"));
        QCOMPARE(m_Host->count("POST", QStringLiteral("/queue")), 0);
    }

    void backgroundCannotAbandonAnUnconfirmedJoin()
    {
        m_Host->holdJoin = true;
        m_Orchestrator->startDesktopSession();
        QTRY_VERIFY(m_Host->pendingJoin);
        QVERIFY(!m_Orchestrator->property("canBackground").toBool());
        bool handedOff = true;
        QVERIFY(QMetaObject::invokeMethod(m_Orchestrator.get(), "followInBackground", Q_RETURN_ARG(bool, handedOff)));
        QVERIFY(!handedOff);
        QVERIFY(m_Orchestrator->active());
        m_Host->finishJoin(200);
        QTRY_VERIFY(m_Orchestrator->property("canBackground").toBool());
        QVERIFY(QMetaObject::invokeMethod(m_Orchestrator.get(), "followInBackground", Q_RETURN_ARG(bool, handedOff)));
        QVERIFY(handedOff);
        m_Orchestrator->cancel();
        QTest::qWait(100);
        QCOMPARE(m_Host->count("POST", QStringLiteral("/queue")), 1);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
    }

    void backgroundTerminalFailureEndsObservationWithoutCancellingTheVm_data()
    {
        QTest::addColumn<int>("httpStatus");
        QTest::addColumn<QString>("code");
        QTest::newRow("TLS") << 503 << QStringLiteral("CLIENT_TLS_ERROR");
        QTest::newRow("permission") << 403 << QStringLiteral("ACCESS_DENIED");
    }

    void backgroundTerminalFailureEndsObservationWithoutCancellingTheVm()
    {
        QFETCH(int, httpStatus);
        QFETCH(QString, code);
        m_Host->state = QStringLiteral("starting");
        publishManagementSession();
        m_Orchestrator->startDesktopSession();
        QTRY_VERIFY(m_Orchestrator->canBackground());
        QSignalSpy notice(m_Api.get(), &LauncherApi::backgroundNotice);
        QSignalSpy ready(m_Api.get(), &LauncherApi::machineBootReady);
        const int before = m_Host->count("GET", QStringLiteral("/status"));
        m_Host->statusFailures = 1;
        m_Host->statusFailureCode = httpStatus;
        m_Host->statusError = {{QStringLiteral("error"), QJsonObject{
            {QStringLiteral("code"), code}}}};
        QVERIFY(m_Orchestrator->followInBackground());
        QTRY_COMPARE(notice.count(), 1);
        m_Host->state = QStringLiteral("ready");
        for (int i = 0; i < 3; i++) {
            m_Api->refreshManagement();
            QTest::qWait(100);
        }
        QCOMPARE(m_Host->count("GET", QStringLiteral("/status")) - before, 1);
        QCOMPARE(ready.count(), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/session")), 0);
        QVERIFY(m_Api->observeMachineStartup(QStringLiteral("chosen"), m_Api->accountGeneration()));
        QTRY_COMPARE(ready.count(), 1);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
    }

    void backgroundTransportRetriesStopAfterThreeConsecutiveReads()
    {
        m_Host->state = QStringLiteral("starting");
        publishManagementSession();
        m_Orchestrator->startDesktopSession();
        QTRY_VERIFY(m_Orchestrator->canBackground());
        QSignalSpy notice(m_Api.get(), &LauncherApi::backgroundNotice);
        QSignalSpy ready(m_Api.get(), &LauncherApi::machineBootReady);
        const int before = m_Host->count("GET", QStringLiteral("/status"));
        m_Host->statusFailures = 100;
        QVERIFY(m_Orchestrator->followInBackground());
        QTRY_VERIFY_WITH_TIMEOUT((m_Api->refreshManagement(), notice.count() == 2), 1500);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/status")) - before, 3);
        m_Host->statusFailures = 0;
        m_Host->state = QStringLiteral("ready");
        for (int i = 0; i < 3; i++) {
            m_Api->refreshManagement();
            QTest::qWait(100);
        }
        QCOMPARE(m_Host->count("GET", QStringLiteral("/status")) - before, 3);
        QCOMPARE(ready.count(), 0);
        QCOMPARE(m_Host->count("POST", QStringLiteral("/queue")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/session")), 0);
    }

    void backgroundRetryBudgetResetsAfterAValidRead()
    {
        m_Host->state = QStringLiteral("starting");
        m_Orchestrator->startDesktopSession();
        QTRY_VERIFY(m_Orchestrator->canBackground());
        const auto starting = m_Host->status();
        m_Host->state = QStringLiteral("ready");
        m_Host->statusSequence = {{503, {}}, {503, {}}, {200, starting},
                                 {503, {}}, {503, {}}, {200, m_Host->status()}};
        const int before = m_Host->count("GET", QStringLiteral("/status"));
        QSignalSpy ready(m_Api.get(), &LauncherApi::machineBootReady);
        QVERIFY(m_Orchestrator->followInBackground());
        QTRY_VERIFY_WITH_TIMEOUT((m_Api->refreshManagement(), ready.count() == 1), 1500);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/status")) - before, 6);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
    }

    void stoppedObservationCannotBeRecreatedByTheSameManagementSession()
    {
        m_Host->state = QStringLiteral("starting");
        publishManagementSession();
        m_Host->holdStatus = true;
        QVERIFY(m_Api->observeMachineStartup(QStringLiteral("chosen"), m_Api->accountGeneration()));
        QTRY_VERIFY(m_Host->pendingStatus);
        m_Api->stopObservingMachineStartup(QStringLiteral("chosen"));
        const int before = m_Host->count("GET", QStringLiteral("/status"));
        QSignalSpy ready(m_Api.get(), &LauncherApi::machineBootReady);
        m_Host->holdStatus = false;
        m_Host->state = QStringLiteral("ready");
        for (int i = 0; i < 3; i++) {
            m_Api->refreshManagement();
            QTest::qWait(100);
        }
        QCOMPARE(m_Host->count("GET", QStringLiteral("/status")), before);
        QCOMPARE(ready.count(), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
    }

    void aDifferentManagementSessionCanBeObservedAfterATerminalFailure()
    {
        m_Host->state = QStringLiteral("starting");
        publishManagementSession();
        m_Orchestrator->startDesktopSession();
        QTRY_VERIFY(m_Orchestrator->canBackground());
        m_Host->statusFailures = 1;
        m_Host->statusFailureCode = 403;
        QSignalSpy notice(m_Api.get(), &LauncherApi::backgroundNotice);
        QVERIFY(m_Orchestrator->followInBackground());
        QTRY_COMPARE(notice.count(), 1);
        m_Api->refreshManagement();
        QTest::qWait(100);
        publishManagementSession(QStringLiteral("next-session"));
        m_Host->state = QStringLiteral("ready");
        auto readyStatus = m_Host->status();
        auto session = readyStatus.value(QStringLiteral("session")).toObject();
        session.insert(QStringLiteral("id"), QStringLiteral("next-session"));
        readyStatus.insert(QStringLiteral("session"), session);
        m_Host->statusOverrides.insert(QStringLiteral("chosen"), readyStatus);
        QSignalSpy ready(m_Api.get(), &LauncherApi::machineBootReady);
        QTRY_VERIFY_WITH_TIMEOUT((m_Api->refreshManagement(), ready.count() == 1), 1500);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
    }

    void twoBackgroundMachinesAreObservedIndependently()
    {
        QSignalSpy notice(m_Api.get(), &LauncherApi::backgroundNotice);
        m_Host->state = QStringLiteral("starting");
        for (const QString& id : {QStringLiteral("chosen"), QStringLiteral("other")}) {
            m_Api->setSelectedMachineId(id);
            QTRY_VERIFY(!m_Api->busy());
            m_Orchestrator->startDesktopSession();
            QTRY_VERIFY(m_Orchestrator->property("canBackground").toBool());
            bool handedOff = false;
            QVERIFY(QMetaObject::invokeMethod(m_Orchestrator.get(), "followInBackground", Q_RETURN_ARG(bool, handedOff)));
            QVERIFY(handedOff);
        }
        m_Host->machineStates.insert(QStringLiteral("chosen"), QStringLiteral("ready"));
        m_Api->refreshManagement();
        QTRY_COMPARE_WITH_TIMEOUT(notice.count(), 1, 1500);
        QVERIFY(notice.at(0).at(0).toString().contains(QStringLiteral("chosen")));
        m_Host->machineStates.insert(QStringLiteral("other"), QStringLiteral("ready"));
        m_Api->refreshManagement();
        QTRY_COMPARE_WITH_TIMEOUT(notice.count(), 2, 1500);
        QVERIFY(notice.at(1).at(0).toString().contains(QStringLiteral("other")));
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/session")), 0);
    }

    void managementSessionAndHistoricalFailureAreNotBootReadiness()
    {
        m_Host->management = {
            {QStringLiteral("machines"), QJsonArray{QJsonObject{
                {QStringLiteral("id"), QStringLiteral("chosen")},
                {QStringLiteral("name"), QStringLiteral("chosen")},
                {QStringLiteral("session"), QJsonObject{{QStringLiteral("id"), QStringLiteral("session-chosen")}}},
                {QStringLiteral("phases"), QJsonObject()},
            }}},
            {QStringLiteral("operations"), QJsonArray{QJsonObject{
                {QStringLiteral("id"), QStringLiteral("historical-failure")},
                {QStringLiteral("machineId"), QStringLiteral("chosen")},
                {QStringLiteral("kind"), QStringLiteral("power_on")},
                {QStringLiteral("state"), QStringLiteral("failed")},
                {QStringLiteral("message"), QStringLiteral("Previous start failed")},
            }}},
        };
        m_Host->state = QStringLiteral("starting");
        m_Orchestrator->startDesktopSession();
        QTRY_VERIFY(m_Orchestrator->property("canBackground").toBool());
        bool handedOff = false;
        QVERIFY(QMetaObject::invokeMethod(m_Orchestrator.get(), "followInBackground", Q_RETURN_ARG(bool, handedOff)));
        QVERIFY(handedOff);
        QSignalSpy ready(m_Api.get(), SIGNAL(machineBootReady(QString,QString)));
        QVERIFY(ready.isValid());
        m_Api->refreshManagement();
        QTest::qWait(100);
        QCOMPARE(ready.count(), 0);
        m_Host->state = QStringLiteral("ready");
        QTRY_VERIFY_WITH_TIMEOUT((m_Api->refreshManagement(), ready.count() == 1), 1500);
        QCOMPARE(ready.first().first().toString(), QStringLiteral("chosen"));
    }

    void aNewManagementSessionIsCheckedBeforeNotifyingReady()
    {
        m_Host->state = QStringLiteral("starting");
        m_Host->management.insert(QStringLiteral("machines"), QJsonArray{QJsonObject{
            {QStringLiteral("id"), QStringLiteral("chosen")},
            {QStringLiteral("name"), QStringLiteral("chosen")},
            {QStringLiteral("session"), QJsonObject{{QStringLiteral("id"), QStringLiteral("session-chosen")}}},
            {QStringLiteral("phases"), QJsonObject()},
        }});
        QSignalSpy ready(m_Api.get(), SIGNAL(machineBootReady(QString,QString)));
        QVERIFY(ready.isValid());
        const int before = m_Host->count("GET", QStringLiteral("/status"));
        m_Api->refreshManagement();
        QTRY_VERIFY_WITH_TIMEOUT(m_Host->count("GET", QStringLiteral("/status")) > before, 1000);
        QCOMPARE(ready.count(), 0);
        m_Host->state = QStringLiteral("ready");
        QTRY_VERIFY_WITH_TIMEOUT((m_Api->refreshManagement(), ready.count() == 1), 1500);
        m_Api->refreshManagement();
        QTest::qWait(100);
        QCOMPARE(ready.count(), 1);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
    }

    void backgroundRejectsReadyForAnotherMachine()
    {
        m_Host->state = QStringLiteral("starting");
        m_Orchestrator->startDesktopSession();
        QTRY_VERIFY(m_Orchestrator->property("canBackground").toBool());
        m_Host->machineStates.insert(QStringLiteral("other"), QStringLiteral("ready"));
        m_Host->statusOverrides.insert(QStringLiteral("chosen"), m_Host->status(QStringLiteral("other")));
        bool handedOff = false;
        QVERIFY(QMetaObject::invokeMethod(m_Orchestrator.get(), "followInBackground", Q_RETURN_ARG(bool, handedOff)));
        QVERIFY(handedOff);
        QSignalSpy ready(m_Api.get(), SIGNAL(machineBootReady(QString,QString)));
        QVERIFY(ready.isValid());
        QTest::qWait(100);
        QCOMPARE(ready.count(), 0);
        m_Host->statusOverrides.clear();
        m_Host->state = QStringLiteral("ready");
        m_Api->refreshManagement();
        QTRY_COMPARE_WITH_TIMEOUT(ready.count(), 1, 1500);
        QCOMPARE(ready.first().first().toString(), QStringLiteral("chosen"));
    }

    void oldBackgroundReplyCannotNotifyAfterLoginToTheSameAccount()
    {
        m_Host->state = QStringLiteral("queued");
        m_Orchestrator->startDesktopSession();
        QTRY_VERIFY(m_Orchestrator->property("canBackground").toBool());
        m_Host->state = QStringLiteral("ready");
        m_Host->heldMachines.insert(QStringLiteral("chosen"));
        bool handedOff = false;
        QVERIFY(QMetaObject::invokeMethod(m_Orchestrator.get(), "followInBackground", Q_RETURN_ARG(bool, handedOff)));
        QVERIFY(handedOff);
        QTRY_VERIFY(m_Host->pendingMachineStatus.value(QStringLiteral("chosen")));
        m_Api->logout();
        QVERIFY(!m_Orchestrator->property("backgrounded").toBool());
        m_Host->state = QStringLiteral("queued");
        m_Host->heldMachines.clear();
        m_Api->login(QStringLiteral("fixture@example.invalid"), QStringLiteral("fixture-only"));
        QTRY_VERIFY(m_Api->loggedIn() && !m_Api->busy());
        QSignalSpy ready(m_Api.get(), SIGNAL(machineBootReady(QString,QString)));
        QVERIFY(ready.isValid());
        m_Host->finishMachineStatus(QStringLiteral("chosen"));
        m_Api->refreshManagement();
        QTest::qWait(100);
        QCOMPARE(ready.count(), 0);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
    }

    void disabledReadyNotificationsKeepForegroundPreparation()
    {
        QSettings().setValue(QStringLiteral("sessionNotify/notifyOnReady"), false);
        QSignalSpy error(m_Orchestrator.get(), &SessionOrchestrator::errorOccurred);
        m_Host->state = QStringLiteral("queued");
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(m_Orchestrator->phase(), QStringLiteral("queued"));
        bool handedOff = true;
        QVERIFY(QMetaObject::invokeMethod(m_Orchestrator.get(), "followInBackground", Q_RETURN_ARG(bool, handedOff)));
        QVERIFY(!handedOff);
        QVERIFY(m_Orchestrator->active());
        QCOMPARE(error.count(), 1);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
    }

    void cancelCompensatesLateQueuePost_data()
    {
        QTest::addColumn<int>("response");
        QTest::newRow("accepted") << 200;
        QTest::newRow("lost-response") << 503;
    }

    void cancelCompensatesLateQueuePost()
    {
        QFETCH(int, response);
        m_Host->holdJoin = true;
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(m_Host->count("POST", QStringLiteral("/queue")), 1);
        QVERIFY(!m_Host->last(QStringLiteral("/queue")).body.contains(QStringLiteral("provider")));
        m_Orchestrator->cancel();
        QVERIFY(!m_Orchestrator->active());
        QVERIFY(m_Orchestrator->busy());
        m_Api->setSelectedMachineId(QStringLiteral("other"));
        m_Host->finishJoin(response);
        QTRY_COMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 1);
        QTRY_VERIFY(!m_Api->busy() && !m_Orchestrator->busy());
        QCOMPARE(QUrlQuery(m_Host->last(QStringLiteral("/queue")).url)
                 .queryItemValue(QStringLiteral("machineId")), QStringLiteral("chosen"));
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/session")), 0);
    }

    void cancellationStopsPinRetriesAndIgnoresOldHostEvents()
    {
        QSignalSpy authenticated(m_Orchestrator.get(), &SessionOrchestrator::pinAuthenticated);
        QSignalSpy error(m_Orchestrator.get(), &SessionOrchestrator::errorOccurred);
        m_Host->state = QStringLiteral("queued");
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(m_Orchestrator->phase(), QStringLiteral("queued"));
        m_Api->submitPairPinForMachine(QStringLiteral("1234"), QStringLiteral("chosen"));
        QTRY_COMPARE(m_Host->count("POST", QStringLiteral("/pair")), 1);
        m_Api->setSelectedMachineId(QStringLiteral("other"));
        m_Orchestrator->cancel();
        QTRY_VERIFY(!m_Api->busy() && !m_Orchestrator->busy());
        QTest::qWait(650);
        QCOMPARE(m_Host->count("POST", QStringLiteral("/pair")), 1);
        QCOMPARE(m_Host->last(QStringLiteral("/pair")).body.value(QStringLiteral("machineId")).toString(),
                 QStringLiteral("chosen"));
        m_Api->setSelectedMachineId(QStringLiteral("chosen"));
        m_Host->state = QStringLiteral("queued");
        QTRY_VERIFY(!m_Api->busy());
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(m_Orchestrator->phase(), QStringLiteral("queued"));
        m_Computers->sessionHostPrepared(1, true);
        m_Computers->sessionPairingCompleted(1, nullptr, QString());
        QVERIFY(m_Orchestrator->active());
        QCOMPARE(authenticated.count(), 0);
        QCOMPARE(error.count(), 0);
    }

    void logoutCompensatesTheLateQueueUsingOnlyTheOriginalAccount()
    {
        m_Host->holdJoin = true;
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(m_Host->count("POST", QStringLiteral("/queue")), 1);
        m_Api->logout();
        m_Api->login(QStringLiteral("other@example.invalid"), QStringLiteral("fixture-only"));
        QTRY_VERIFY(m_Api->loggedIn() && !m_Api->busy());
        QVERIFY(!m_Orchestrator->active());
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
        m_Host->finishJoin(200);
        QTRY_COMPARE_WITH_TIMEOUT(m_Host->count("DELETE", QStringLiteral("/queue")), 1, 1000);
        QCOMPARE(m_Host->last(QStringLiteral("/queue")).authorization, QByteArray("Bearer fixture-access"));
        QCOMPARE(QUrlQuery(m_Host->last(QStringLiteral("/queue")).url)
                 .queryItemValue(QStringLiteral("machineId")), QStringLiteral("chosen"));
        QTRY_VERIFY(!m_Orchestrator->busy());
        QVERIFY(m_Api->loggedIn());
        QCOMPARE(m_Api->email(), QStringLiteral("other@example.invalid"));
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/session")), 0);
    }

    void anInPlaceLoginCancelsOldPreparation()
    {
        m_Host->state = QStringLiteral("queued");
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(m_Orchestrator->phase(), QStringLiteral("queued"));
        QTRY_VERIFY(!m_Api->busy());
        m_Api->login(QStringLiteral("other@example.invalid"), QStringLiteral("fixture-only"));
        QTRY_VERIFY(m_Api->email() == QStringLiteral("other@example.invalid") && !m_Api->busy());
        QVERIFY(!m_Orchestrator->active());
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
    }

    void nativeSessionsIdentifyOnlyManagedHostsAfterTheFirstFrame_data()
    {
        QTest::addColumn<QString>("origin");
        QTest::addColumn<bool>("hasControls");
        QTest::addColumn<bool>("owner");
        QTest::newRow("owned") << QStringLiteral("launcher") << true << true;
        QTest::newRow("friend") << QStringLiteral("friend") << true << false;
        QTest::newRow("manual") << QString() << false << false;
    }

    void nativeSessionsIdentifyOnlyManagedHostsAfterTheFirstFrame()
    {
        QFETCH(QString, origin);
        QFETCH(bool, hasControls);
        QFETCH(bool, owner);
        NvComputer computer{};
        computer.scMachineId = QStringLiteral("frozen-vm");
        computer.scOrigin = origin;
        NvApp app;
        app.id = 1;
        app.name = QStringLiteral("Desktop");
        Session session(&computer, app);
        session.initializeStreamControls(m_Api.get(), m_Api->accountGeneration());
        auto* controls = qobject_cast<StreamGuestController*>(session.streamGuests());
        QCOMPARE(controls != nullptr, hasControls);
        QCOMPARE(m_Host->count("POST", QStringLiteral("/stream/identity/challenge")), 0);
        if (!controls) return;
        QCOMPARE(controls->ownerSession(), owner);
        m_Api->setSelectedMachineId(QStringLiteral("other"));
        session.notifyFirstVideoFrame();
        QTRY_COMPARE_WITH_TIMEOUT(m_Host->count("POST", QStringLiteral("/stream/identity")), 1, 1000);
        QCOMPARE(m_Host->last(QStringLiteral("/stream/identity")).body.value(QStringLiteral("machineId")).toString(),
                 QStringLiteral("frozen-vm"));
        session.sessionFinished(0);
        QVERIFY(!controls->ownerSession());
        QVERIFY(!controls->busy());
    }

    void aFinishedOrObsoleteSessionCannotIdentifyUsingANewLogin()
    {
        const quint64 account = m_Api->accountGeneration();
        NvComputer computer{};
        computer.scMachineId = QStringLiteral("old-vm");
        computer.scOrigin = QStringLiteral("launcher");
        NvApp app;
        app.id = 1;
        Session finished(&computer, app);
        finished.initializeStreamControls(m_Api.get(), account);
        QVERIFY(finished.streamGuests());
        finished.sessionFinished(0);
        finished.notifyFirstVideoFrame();
        m_Api->logout();
        m_Api->login(QStringLiteral("other@example.invalid"), QStringLiteral("fixture-only"));
        QTRY_VERIFY(m_Api->loggedIn() && !m_Api->busy());
        Session obsolete(&computer, app);
        obsolete.initializeStreamControls(m_Api.get(), account);
        obsolete.notifyFirstVideoFrame();
        QTest::qWait(50);
        QCOMPARE(m_Host->count("POST", QStringLiteral("/stream/identity/challenge")), 0);
    }

    void packagedGuestWindowUsesTheRealSessionControllerAndClearsOnAccountChange()
    {
        NvComputer computer{};
        computer.scMachineId = QStringLiteral("frozen-vm");
        computer.scOrigin = QStringLiteral("launcher");
        NvApp app;
        app.id = 1;
        Session session(&computer, app);
        session.initializeStreamControls(m_Api.get(), m_Api->accountGeneration());
        QQmlEngine engine;
        QQmlComponent component(&engine, QUrl(QStringLiteral("qrc:/gui/StreamSideMenuWindow.qml")));
        QTRY_VERIFY2(component.status() != QQmlComponent::Loading, qPrintable(component.errorString()));
        QVERIFY2(component.isReady(), qPrintable(component.errorString()));
        QScopedPointer<QObject> menu(component.createWithInitialProperties({
            {QStringLiteral("session"), QVariant::fromValue(&session)},
            {QStringLiteral("reducedMotion"), true}
        }));
        QVERIFY2(menu, qPrintable(component.errorString()));
        QCOMPARE(menu->property("controller").value<QObject*>(), session.streamGuests());
        auto* controller = qobject_cast<StreamGuestController*>(session.streamGuests());
        QVERIFY(controller && controller->ownerSession());
        session.notifyFirstVideoFrame();
        QTRY_COMPARE_WITH_TIMEOUT(m_Host->count("POST", QStringLiteral("/stream/identity")), 1, 1000);
        m_Api->logout();
        QVERIFY(!controller->ownerSession());
        auto* window = qobject_cast<QQuickWindow*>(menu.data());
        QVERIFY(window && !window->isVisible());
        session.sessionFinished(0);
    }

    void readyNotificationPreservesTheCurrentStreamViewAndSdlInputOwner()
    {
        qmlRegisterType<ComputerModel>("ComputerModel", 1, 0, "ComputerModel");
        qmlRegisterType<AppModel>("AppModel", 1, 0, "AppModel");
        qmlRegisterType<SessionOrchestrator>("SessionOrchestrator", 1, 0, "SessionOrchestrator");
        qmlRegisterUncreatableType<Session>("Session", 1, 0, "Session", "Created by the native session owner");
        qmlRegisterSingletonType<ComputerManager>("ComputerManager", 1, 0, "ComputerManager",
            [](QQmlEngine* engine, QJSEngine*) -> QObject* {
                auto* object = engine->rootContext()->contextProperty("fixtureComputers").value<QObject*>();
                QQmlEngine::setObjectOwnership(object, QQmlEngine::CppOwnership);
                return object;
            });
        qmlRegisterSingletonType<LauncherApi>("LauncherApi", 1, 0, "LauncherApi",
            [](QQmlEngine* engine, QJSEngine*) -> QObject* {
                auto* object = engine->rootContext()->contextProperty("fixtureApi").value<QObject*>();
                QQmlEngine::setObjectOwnership(object, QQmlEngine::CppOwnership);
                return object;
            });
        qmlRegisterSingletonType<AutoUpdateChecker>("AutoUpdateChecker", 1, 0, "AutoUpdateChecker",
            [](QQmlEngine*, QJSEngine*) -> QObject* { return new AutoUpdateChecker(); });
        qmlRegisterSingletonType<MicLevelTester>("MicLevelTester", 1, 0, "MicLevelTester",
            [](QQmlEngine*, QJSEngine*) -> QObject* { return new MicLevelTester(); });
        qmlRegisterSingletonType<SystemProperties>("SystemProperties", 1, 0, "SystemProperties",
            [](QQmlEngine*, QJSEngine*) -> QObject* { return new SystemProperties(); });
        qmlRegisterSingletonType<SdlGamepadKeyNavigation>("SdlGamepadKeyNavigation", 1, 0, "SdlGamepadKeyNavigation",
            [](QQmlEngine* engine, QJSEngine*) -> QObject* {
                return new SdlGamepadKeyNavigation(StreamingPreferences::get(engine));
            });
        qmlRegisterSingletonType<StreamingPreferences>("StreamingPreferences", 1, 0, "StreamingPreferences",
            [](QQmlEngine* engine, QJSEngine*) -> QObject* { return StreamingPreferences::get(engine); });
        qmlRegisterSingletonType<XdApi>("XdApi", 1, 0, "XdApi",
            [](QQmlEngine*, QJSEngine*) -> QObject* { return new XdApi(); });
        qmlRegisterSingletonType<LatencyTester>("LatencyTester", 1, 0, "LatencyTester",
            [](QQmlEngine*, QJSEngine*) -> QObject* { return new LatencyTester(); });

        NvComputer computer{};
        computer.scMachineId = QStringLiteral("chosen");
        computer.scOrigin = QStringLiteral("launcher");
        NvApp app;
        app.id = 1;
        Session session(&computer, app);
        session.initializeStreamControls(m_Api.get(), m_Api->accountGeneration());
        QQmlIncubationController incubation;
        QQmlEngine engine;
        // Keep only the remote stream loader pending; all navigation and SDL ownership are real.
        engine.setIncubationController(&incubation);
        engine.rootContext()->setContextProperty(QStringLiteral("fixtureApi"), m_Api.get());
        engine.rootContext()->setContextProperty(QStringLiteral("fixtureComputers"), m_Computers.get());
        engine.rootContext()->setContextProperty(QStringLiteral("fixtureSession"), &session);
        QQmlComponent initial(&engine);
        initial.setData("import QtQuick 2.0; Item {}", QUrl());
        engine.rootContext()->setContextProperty(QStringLiteral("initialView"), &initial);
        QQmlComponent component(&engine, QUrl(QStringLiteral("qrc:/gui/main.qml")));
        QTRY_VERIFY2(component.status() != QQmlComponent::Loading, qPrintable(component.errorString()));
        QVERIFY2(component.isReady(), qPrintable(component.errorString()));
        QScopedPointer<QObject> window(component.create());
        QVERIFY2(window, qPrintable(component.errorString()));
        QCOMPARE(SDL_InitSubSystem(SDL_INIT_EVENTS), 0);
        const auto eventSubsystem = qScopeGuard([]() { SDL_QuitSubSystem(SDL_INIT_EVENTS); });
        auto evaluate = [&](const QString& code) {
            QQmlExpression expression(qmlContext(window.data()), window.data(), code);
            const auto result = expression.evaluate();
            if (expression.hasError()) qFatal("%s", qPrintable(expression.error().toString()));
            return result;
        };
        evaluate(QStringLiteral("stackView.push('qrc:/gui/StreamSegue.qml',"
            " {session: fixtureSession, managedDesktop: true}, StackView.Immediate);"
            " SdlGamepadKeyNavigation.disable()"));
        QPointer<QObject> segue = evaluate(QStringLiteral("stackView.currentItem")).value<QObject*>();
        QVERIFY(segue);
        QVERIFY(incubation.incubatingObjectCount() > 0);
        const Uint32 eventType = SDL_RegisterEvents(1);
        QVERIFY(eventType != Uint32(-1));
        SDL_Event event{};
        event.type = eventType;
        QCOMPARE(SDL_PushEvent(&event), 1);
        m_Api->machineBootReady(QStringLiteral("other"), QStringLiteral("Other VM ready"));
        evaluate(QStringLiteral("operationsTray.messageClicked()"));
        QTest::qWait(100);
        QVERIFY2(segue, "A readiness notification destroyed the current stream view");
        QCOMPARE(evaluate(QStringLiteral("stackView.currentItem")).value<QObject*>(), segue.data());
        QCOMPARE(SDL_PeepEvents(&event, 1, SDL_GETEVENT, eventType, eventType), 1);
        QCOMPARE(m_Api->selectedMachineId(), QStringLiteral("other"));
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
        session.sessionFinished(0);
    }

    void connectionFailureWithoutMessageReturnsToDashboard_data()
    {
        QTest::addColumn<QJsonObject>("response");
        QTest::newRow("missing") << QJsonObject();
        QTest::newRow("blank") << QJsonObject{{QStringLiteral("error"),
            QJsonObject{{QStringLiteral("message"), QStringLiteral("   ")}}}};
    }

    void selectionDoesNotStartOrStopEitherMachineAndRejectsLateStatus()
    {
        m_Host->state = QStringLiteral("ready");
        m_Host->holdStatus = true;
        m_Api->refreshStatus();
        QTRY_VERIFY(!m_Host->pendingStatus.isNull());
        m_Api->setSelectedMachineId(QStringLiteral("other"));
        QVERIFY(m_Api->statusMachineId().isEmpty());
        m_Host->holdStatus = false;
        m_Host->finishStatus();
        QTRY_COMPARE(m_Api->statusMachineId(), QStringLiteral("other"));
        QCOMPARE(m_Host->count("POST", QStringLiteral("/queue")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/session")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
    }

    void cancellingAfterSelectionChangesPreservesAnIndependentQueue()
    {
        m_Host->state = QStringLiteral("queued");
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(m_Orchestrator->phase(), QStringLiteral("queued"));
        m_Api->setSelectedMachineId(QStringLiteral("other"));
        QVERIFY(m_Orchestrator->active());
        m_Orchestrator->cancel();
        QVERIFY(!m_Orchestrator->active());
        QTRY_VERIFY(!m_Api->busy());
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/queue")), 0);
        QCOMPARE(m_Host->count("DELETE", QStringLiteral("/session")), 0);
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 0);
    }

    void connectionFailureWithoutMessageReturnsToDashboard()
    {
        QFETCH(QJsonObject, response);
        QSignalSpy error(m_Orchestrator.get(), &SessionOrchestrator::errorOccurred);
        m_Host->state = QStringLiteral("ready");
        m_Host->holdConnection = false;
        m_Host->connectionError = response;
        m_Orchestrator->startDesktopSession();
        QTRY_COMPARE(error.count(), 1);
        QVERIFY(!error.first().first().toString().isEmpty());
        QVERIFY(!m_Orchestrator->active());
        QVERIFY(!m_Orchestrator->busy());
        QCOMPARE(m_Host->count("GET", QStringLiteral("/connection")), 1);
    }
};

QTEST_MAIN(SessionOrchestratorTest)
#include "session_orchestrator_test.moc"
