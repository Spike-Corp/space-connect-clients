#pragma once

#include <QByteArray>
#include <QJsonObject>
#include <QString>

struct LauncherStatus
{
    QString state;
    int queuePosition = 0;
    int queueTotal = 0;
    int priority = 0;
    QString planSlug;
    QString machineName;
    // Id da máquina da sessão ativa (quando há). Usado pra multi-máquina:
    // conectar/encerrar miram a VM da sessão, não a selecionada na lista.
    QString machineId;
    QString creationPhase;
    qint64 remainingMs = 0;
    // WS3: retentativas de boot SPOT visíveis ("tentativa X/15" / "esgotou tentativas").
    int bootAttempt = 0;
    int bootAttemptMax = 0;
    bool bootAttemptsExhausted = false;
    QString blockedReason;
    // WS14: aviso de provisória. provisoria = esta VM É a provisória proxmox que o
    // cliente está usando (GCP sem capacidade); provisoriaOriginalPlan = slug GCP
    // guardado; hasProvisoria = esta é a GCP original com uma provisória ativa no lugar.
    bool provisoria = false;
    QString provisoriaOriginalPlan;
    bool hasProvisoria = false;
};

struct LauncherConnection
{
    QString sessionId;
    QString machineId;
    QString machineName;
    QString host;
    QString ipv6;
    int port = 0;
    // Bitrate ceiling (Kbps) reported by the backend from the machine's provider/plan:
    // proxmox physical host = 100000, cloud VM = 25000. 0 when an older backend omits it.
    int maxBitrateKbps = 0;
    int recommendedBitrateKbps = 0;
};

class LauncherJson
{
public:
    static LauncherStatus parseStatus(const QByteArray& json);
    static LauncherConnection parseConnection(const QByteArray& json);
    static bool pairPinAccepted(int status, const QJsonObject& response);
};
