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
};

QTEST_APPLESS_MAIN(LauncherJsonTest)
#include "launcherjson_test.moc"
