#include "launcherjson.h"

#include <QJsonDocument>
#include <QJsonObject>

#include <stdexcept>

static QJsonObject parseObject(const QByteArray& json)
{
    QJsonParseError error;
    const QJsonDocument document = QJsonDocument::fromJson(json, &error);
    if (error.error != QJsonParseError::NoError || !document.isObject()) {
        throw std::runtime_error("Invalid SpaceCloud response");
    }
    return document.object();
}

LauncherStatus LauncherJson::parseStatus(const QByteArray& json)
{
    const QJsonObject root = parseObject(json);
    LauncherStatus status;
    status.state = root.value(QStringLiteral("state")).toString();

    const QJsonObject queue = root.value(QStringLiteral("queue")).toObject();
    status.queuePosition = queue.value(QStringLiteral("position")).toInt();
    status.queueTotal = queue.value(QStringLiteral("total")).toInt();
    status.priority = queue.value(QStringLiteral("priority")).toInt();
    status.planSlug = queue.value(QStringLiteral("planSlug")).toString();

    const QJsonObject session = root.value(QStringLiteral("session")).toObject();
    status.remainingMs = static_cast<qint64>(
        session.value(QStringLiteral("remainingMs")).toDouble());
    const QJsonObject machine = session.value(QStringLiteral("machine")).toObject();
    status.machineName = machine.value(QStringLiteral("name")).toString();
    // Fase de criação/boot da VM (protegendo ambiente, anexando GPU, etc.) — o app
    // mostra isso em vez de um "Your PC is starting" genérico.
    status.creationPhase = machine.value(QStringLiteral("creationPhase")).toString();
    return status;
}

LauncherConnection LauncherJson::parseConnection(const QByteArray& json)
{
    const QJsonObject root = parseObject(json);
    LauncherConnection connection;
    connection.sessionId = root.value(QStringLiteral("sessionId")).toString();
    connection.machineId = root.value(QStringLiteral("machineId")).toString();
    connection.machineName = root.value(QStringLiteral("machineName")).toString();
    connection.host = root.value(QStringLiteral("host")).toString();
    connection.ipv6 = root.value(QStringLiteral("ipv6")).toString();
    connection.port = root.value(QStringLiteral("port")).toInt();
    connection.maxBitrateKbps = root.value(QStringLiteral("maxBitrateKbps")).toInt();
    connection.recommendedBitrateKbps = root.value(QStringLiteral("recommendedBitrateKbps")).toInt();
    return connection;
}
