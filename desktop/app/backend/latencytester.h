#pragma once

#include <QObject>
#include <QNetworkAccessManager>
#include <QElapsedTimer>
#include <QVector>

/**
 * Teste de latência/rede estilo GeForce Now — paridade com o app Android
 * (LatencyTestActivity). Mede o tempo de resposta (request + primeira resposta)
 * contra endpoints de borda (Cloudflare + Google) e classifica a qualidade da
 * conexão pra cloud gaming. Não mede a VM em si (essa só existe com sessão
 * ativa) — mede a qualidade do link do usuário até a internet.
 *
 * Assíncrono (não trava a UI): startTest() dispara os pings e emite
 * progressChanged a cada alvo medido e finished() ao final com mediana/jitter.
 */
class LatencyTester : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool running READ running NOTIFY runningChanged)
    Q_PROPERTY(QString progressText READ progressText NOTIFY progressChanged)
    Q_PROPERTY(long medianMs READ medianMs NOTIFY finished)
    Q_PROPERTY(long minMs READ minMs NOTIFY finished)
    Q_PROPERTY(long maxMs READ maxMs NOTIFY finished)
    Q_PROPERTY(long jitterMs READ jitterMs NOTIFY finished)
    Q_PROPERTY(int sampleCount READ sampleCount NOTIFY finished)

public:
    explicit LatencyTester(QObject* parent = nullptr);

    bool running() const { return m_Running; }
    QString progressText() const { return m_ProgressText; }
    long medianMs() const { return m_MedianMs; }
    long minMs() const { return m_MinMs; }
    long maxMs() const { return m_MaxMs; }
    long jitterMs() const { return m_JitterMs; }
    int sampleCount() const { return m_Samples.size(); }

    Q_INVOKABLE void startTest();

signals:
    void runningChanged();
    void progressChanged();
    void finished();

private:
    void pingNext();
    void finishTest();
    static long medianOf(QVector<long> v);

    QNetworkAccessManager m_Nam;
    QVector<QString> m_Targets;
    QVector<long> m_Samples;   // melhor ping de cada alvo
    int m_TargetIndex = 0;
    int m_PingIndex = 0;
    long m_BestForTarget = -1;
    bool m_Running = false;
    QString m_ProgressText;
    long m_MedianMs = -1, m_MinMs = -1, m_MaxMs = -1, m_JitterMs = -1;
    QElapsedTimer m_Timer;

    static constexpr int PINGS_PER_TARGET = 4;
    static constexpr int TIMEOUT_MS = 4000;
};
