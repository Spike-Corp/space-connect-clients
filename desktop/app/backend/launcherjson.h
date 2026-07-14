#pragma once

#include <QByteArray>
#include <QString>

struct LauncherStatus
{
    QString state;
    int queuePosition = 0;
    int queueTotal = 0;
    int priority = 0;
    QString planSlug;
    QString machineName;
    qint64 remainingMs = 0;
};

struct LauncherConnection
{
    QString sessionId;
    QString machineId;
    QString machineName;
    QString host;
    QString ipv6;
    int port = 0;
};

class LauncherJson
{
public:
    static LauncherStatus parseStatus(const QByteArray& json);
    static LauncherConnection parseConnection(const QByteArray& json);
};
