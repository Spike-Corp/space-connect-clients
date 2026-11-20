#pragma once

#include "nvapp.h"
#include <QVector>
#include <QVariant>
#include <QVariantList>
#include <QVariantMap>

namespace DesktopLaunch {
inline bool isDesktopName(const QString& name)
{
    return name.compare(QStringLiteral("Desktop"), Qt::CaseInsensitive) == 0 ||
           name.compare(QString::fromUtf8("Área de trabalho"), Qt::CaseInsensitive) == 0 ||
           name.compare(QStringLiteral("Area de trabalho"), Qt::CaseInsensitive) == 0;
}

inline bool isVirtualDisplayName(const QString& name)
{
    // Nome real do app no Apollo/Moonlight nas VMs "Sudo Maker" (SudoVDA):
    // "Virtual Display" (+ variações/traduções plausíveis). Aqui é comparação
    // exata, então NÃO conflita com isDesktopName (que casa "Desktop" exato).
    return name.compare(QStringLiteral("Virtual Display"), Qt::CaseInsensitive) == 0 ||
           name.compare(QStringLiteral("Virtual Desktop"), Qt::CaseInsensitive) == 0 ||
           name.compare(QStringLiteral("Display Virtual"), Qt::CaseInsensitive) == 0 ||
           name.compare(QString::fromUtf8("Área de trabalho virtual"), Qt::CaseInsensitive) == 0;
}

// Escolhe o app a lançar no fluxo "managed desktop".
// preferVirtualDisplay (modo performance / Sudo Maker): tenta primeiro o app
// "Virtual Display" (resoluções desbloqueadas via SudoVDA); se a VM não tiver,
// cai no "Desktop" normal — seguro em qualquer máquina. Modo padrão abre Desktop.
inline int appIndex(const QVector<NvApp>& apps, bool preferVirtualDisplay = false)
{
    if (preferVirtualDisplay) {
        for (int i = 0; i < apps.size(); ++i) {
            if (apps[i].id > 0 && isVirtualDisplayName(apps[i].name.trimmed())) {
                return i;
            }
        }
    }
    for (int i = 0; i < apps.size(); ++i) {
        if (apps[i].id > 0 && isDesktopName(apps[i].name.trimmed())) {
            return i;
        }
    }
    return -1;
}

// Lê, da lista exposta por LauncherApi::machines() (itens QVariantMap com "id" e
// "displayMode"), se a máquina está em modo "performance" (Sudo Maker) — ou seja,
// se o auto-launch deve preferir o app "Virtual Display". Default seguro: false
// (modo padrão → Desktop) quando o id é vazio, a máquina não está na lista, ou o
// displayMode não é exatamente "performance".
inline bool preferVirtualDisplayForMachine(const QVariantList& machines, const QString& machineId)
{
    if (machineId.isEmpty()) {
        return false;
    }
    for (const QVariant& entry : machines) {
        const QVariantMap machine = entry.toMap();
        if (machine.value(QStringLiteral("id")).toString() == machineId) {
            return machine.value(QStringLiteral("displayMode")).toString() ==
                   QStringLiteral("performance");
        }
    }
    return false;
}
}
