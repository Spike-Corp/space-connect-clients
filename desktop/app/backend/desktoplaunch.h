#pragma once

#include "nvapp.h"
#include <QVector>

namespace DesktopLaunch {
inline int appIndex(const QVector<NvApp>& apps)
{
    for (int i = 0; i < apps.size(); ++i) {
        const QString name = apps[i].name.trimmed();
        if (apps[i].id > 0 &&
            (name.compare(QStringLiteral("Desktop"), Qt::CaseInsensitive) == 0 ||
             name.compare(QString::fromUtf8("Área de trabalho"), Qt::CaseInsensitive) == 0 ||
             name.compare(QStringLiteral("Area de trabalho"), Qt::CaseInsensitive) == 0)) {
            return i;
        }
    }
    return -1;
}
}
