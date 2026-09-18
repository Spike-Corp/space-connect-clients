#include "latencytester.h"

#include <QNetworkRequest>
#include <QNetworkReply>
#include <QUrl>
#include <QDateTime>
#include <algorithm>
#include <QtGlobal>

// Mesmos endpoints de borda do Android (resposta mínima / sem corpo = rede pura).
static const char* kTargets[] = {
    "https://www.gstatic.com/generate_204",
    "https://connectivitycheck.gstatic.com/generate_204",
    "https://cloudflare.com/cdn-cgi/trace",
    "https://www.google.com.br/generate_204",
};

LatencyTester::LatencyTester(QObject* parent)
    : QObject(parent)
{
    for (const char* t : kTargets) {
        m_Targets.append(QString::fromLatin1(t));
    }
}

void LatencyTester::startTest()
{
    if (m_Running) {
        return;
    }
    m_Running = true;
    m_Samples.clear();
    m_TargetIndex = 0;
    m_PingIndex = 0;
    m_BestForTarget = -1;
    m_MedianMs = m_MinMs = m_MaxMs = m_JitterMs = -1;
    m_ProgressText = tr("Measuring…");
    emit runningChanged();
    emit progressChanged();
    pingNext();
}

void LatencyTester::pingNext()
{
    // Acabaram os alvos → calcula e emite o resultado final.
    if (m_TargetIndex >= m_Targets.size()) {
        finishTest();
        return;
    }

    const QString base = m_Targets.at(m_TargetIndex);
    QUrl url(base + (base.contains('?') ? "&" : "?") + "_t=" + QString::number(QDateTime::currentMSecsSinceEpoch()) + QString::number(m_PingIndex));
    QNetworkRequest req(url);
    req.setHeader(QNetworkRequest::UserAgentHeader, "SpaceConnect-Qt/0.1.9");
    // Sem cache nem redirect: queremos o tempo de rede cru até a borda.
    req.setAttribute(QNetworkRequest::CacheLoadControlAttribute, QNetworkRequest::AlwaysNetwork);
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    req.setTransferTimeout(TIMEOUT_MS);

    m_Timer.start();
    QNetworkReply* reply = m_Nam.get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const long ms = m_Timer.isValid() ? m_Timer.elapsed() : -1;
        const bool ok = (reply->error() == QNetworkReply::NoError) && ms >= 0;
        reply->deleteLater();

        if (ok && (m_BestForTarget < 0 || ms < m_BestForTarget)) {
            m_BestForTarget = ms;
        }

        m_PingIndex++;
        if (m_PingIndex < PINGS_PER_TARGET) {
            pingNext(); // próximo ping do mesmo alvo
            return;
        }

        // Alvo concluído: guarda o melhor ping e publica o progresso.
        if (m_BestForTarget >= 0) {
            m_Samples.append(m_BestForTarget);
            const QString host = QUrl(m_Targets.at(m_TargetIndex)).host();
            m_ProgressText = host + "  →  " + QString::number(m_BestForTarget) + " ms";
            emit progressChanged();
        }
        m_TargetIndex++;
        m_PingIndex = 0;
        m_BestForTarget = -1;
        pingNext(); // próximo alvo
    });
}

void LatencyTester::finishTest()
{
    m_Running = false;
    if (!m_Samples.isEmpty()) {
        long mn = m_Samples.first(), mx = m_Samples.first();
        for (long v : m_Samples) {
            mn = qMin(mn, v);
            mx = qMax(mx, v);
        }
        m_MinMs = mn;
        m_MaxMs = mx;
        m_JitterMs = mx - mn;
        m_MedianMs = medianOf(m_Samples);
    }
    emit runningChanged();
    emit finished();
}

long LatencyTester::medianOf(QVector<long> v)
{
    if (v.isEmpty()) {
        return -1;
    }
    std::sort(v.begin(), v.end());
    const int n = v.size();
    return (n % 2 == 1) ? v.at(n / 2) : (v.at(n / 2 - 1) + v.at(n / 2)) / 2;
}
