#include <QtTest>

#include "../backend/launcherjson.h"

class LauncherJsonTest : public QObject
{
    Q_OBJECT

private slots:
    void parsesQueuedStatus()
    {
        const QByteArray json = R"({
            "state":"queued",
            "queue":{"position":2,"total":7,"priority":5,"planSlug":"builder-pro"},
            "session":null
        })";

        const LauncherStatus status = LauncherJson::parseStatus(json);

        QCOMPARE(status.state, QStringLiteral("queued"));
        QCOMPARE(status.queuePosition, 2);
        QCOMPARE(status.queueTotal, 7);
        QCOMPARE(status.planSlug, QStringLiteral("builder-pro"));
    }

    void parsesBootAttemptFields()
    {
        const QByteArray json = R"({
            "state":"queued",
            "queue":{"position":1,"total":3,"priority":5,"planSlug":"builder-pro",
                     "bootAttempt":4,"bootAttemptMax":15,"bootAttemptsExhausted":false},
            "session":null
        })";

        const LauncherStatus status = LauncherJson::parseStatus(json);

        QCOMPARE(status.bootAttempt, 4);
        QCOMPARE(status.bootAttemptMax, 15);
        QCOMPARE(status.bootAttemptsExhausted, false);
    }

    void parsesBootAttemptsExhausted()
    {
        const QByteArray json = R"({
            "state":"queued",
            "queue":{"position":1,"total":3,"priority":5,"planSlug":"builder-pro",
                     "bootAttempt":15,"bootAttemptMax":15,"bootAttemptsExhausted":true,
                     "blockedReason":"exhausted"},
            "session":null
        })";

        const LauncherStatus status = LauncherJson::parseStatus(json);

        QCOMPARE(status.bootAttempt, 15);
        QCOMPARE(status.bootAttemptsExhausted, true);
        QCOMPARE(status.blockedReason, QStringLiteral("exhausted"));
    }

    void parsesConnectionWithoutPrivatePorts()
    {
        const QByteArray json = R"({
            "sessionId":"session-1",
            "machineId":"machine-1",
            "machineName":"Space PC",
            "host":"203.0.113.10",
            "port":48000
        })";

        const LauncherConnection connection = LauncherJson::parseConnection(json);

        QCOMPARE(connection.host, QStringLiteral("203.0.113.10"));
        QCOMPARE(connection.port, 48000);
        QCOMPARE(connection.machineName, QStringLiteral("Space PC"));
    }
    void parsesProvisoriaFromSessionMachine()
    {
        // WS14: o app avisa quando o cliente está numa provisória proxmox (GCP sem
        // capacidade). Os campos vêm em session.machine do /launcher/v1/status.
        const QByteArray json = R"({
            "state":"ready",
            "queue":null,
            "session":{"remainingMs":60000,"machine":{
                "id":"prox-1","name":"spk-builder-lite-nw-x","creationPhase":null,
                "provisoria":true,"provisoriaOriginalPlan":"t4-azuli","hasProvisoria":false}}
        })";

        const LauncherStatus status = LauncherJson::parseStatus(json);

        QCOMPARE(status.provisoria, true);
        QCOMPARE(status.provisoriaOriginalPlan, QStringLiteral("t4-azuli"));
        QCOMPARE(status.hasProvisoria, false);
    }

    void parsesHasProvisoriaOnOriginalGcp()
    {
        const QByteArray json = R"({
            "state":"ready",
            "queue":null,
            "session":{"remainingMs":60000,"machine":{
                "id":"cloud-1","name":"spk-t4-azuli-x","creationPhase":null,
                "provisoria":false,"provisoriaOriginalPlan":null,"hasProvisoria":true}}
        })";

        const LauncherStatus status = LauncherJson::parseStatus(json);

        QCOMPARE(status.provisoria, false);
        QCOMPARE(status.hasProvisoria, true);
    }
};

QTEST_APPLESS_MAIN(LauncherJsonTest)
#include "launcherjson_test.moc"
