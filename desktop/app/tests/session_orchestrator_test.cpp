#include <QtTest>
#include <QJsonArray>
#include <QPointer>
#include <QSettings>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QUrlQuery>
#include <memory>

#include "../backend/computermanager.h"
#include "../backend/launcherapi.h"
#include "../backend/sessionorchestrator.h"
#include "../settings/streamingpreferences.h"

class LauncherFixture
{
public:
    struct Request {
        QByteArray method;
        QUrl url;
        QJsonObject body;
    };

    QTcpServer server;
    QList<Request> requests;
    QString state = QStringLiteral("idle");
    bool holdJoin = false;
    bool holdConnection = true;
    bool holdStatus = false;
    int connectionStatus = 503;
    QJsonObject connectionError;
    QPointer<QTcpSocket> pendingJoin;
    QPointer<QTcpSocket> pendingConnection;
    QPointer<QTcpSocket> pendingStatus;
    QJsonObject pendingStatusBody;

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
                for (const QByteArray& header : input->left(headerEnd).split('\n')) {
                    if (header.toLower().startsWith("content-length:"))
                        length = header.mid(15).trimmed().toInt();
                }
                if (input->size() < headerEnd + 4 + length)
                    return;
                const auto line = input->left(input->indexOf("\r\n")).split(' ');
                const Request request{line[0], QUrl::fromEncoded(line[1]),
                    QJsonDocument::fromJson(input->mid(headerEnd + 4, length)).object()};
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

    QJsonObject status() const
    {
        QJsonObject result{{QStringLiteral("state"), state}};
        if (state == QStringLiteral("queued")) {
            result.insert(QStringLiteral("queue"), QJsonObject{
                {QStringLiteral("position"), 2}, {QStringLiteral("total"), 4}});
            result.insert(QStringLiteral("bootTexts"), QJsonObject{
                {QStringLiteral("queueTitle"), QStringLiteral("Fila {position} de {total}")}});
        }
        if (state == QStringLiteral("starting") || state == QStringLiteral("ready")) {
            result.insert(QStringLiteral("session"), QJsonObject{
                {QStringLiteral("machine"), QJsonObject{
                    {QStringLiteral("id"), QStringLiteral("chosen")},
                    {QStringLiteral("creationPhase"), QStringLiteral("booting")},
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
                {QStringLiteral("accessToken"), QStringLiteral("fixture-access")},
                {QStringLiteral("refreshToken"), QStringLiteral("fixture-refresh")},
                {QStringLiteral("accessTokenExpiresIn"), 900},
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
            QJsonObject body = status();
            if (state == QStringLiteral("starting") || state == QStringLiteral("ready")) {
                QJsonObject session = body.value(QStringLiteral("session")).toObject();
                QJsonObject machine = session.value(QStringLiteral("machine")).toObject();
                machine.insert(QStringLiteral("id"), QUrlQuery(request.url).queryItemValue(QStringLiteral("machineId")));
                session.insert(QStringLiteral("machine"), machine);
                body.insert(QStringLiteral("session"), session);
            }
            if (holdStatus) { pendingStatus = socket; pendingStatusBody = body; }
            else write(socket, 200, body);
        }
        else if (path == QStringLiteral("/vm-management")) {
            write(socket, 200, QJsonObject{{QStringLiteral("machines"), QJsonArray()}, {QStringLiteral("operations"), QJsonArray()}});
        }
        else if (path == QStringLiteral("/queue") && request.method == "POST") {
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

private slots:
    void initTestCase()
    {
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
