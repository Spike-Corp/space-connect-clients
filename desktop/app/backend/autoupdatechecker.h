#pragma once

#include <QObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>

class AutoUpdateChecker : public QObject
{
    Q_OBJECT
public:
    explicit AutoUpdateChecker(QObject *parent = nullptr);

    Q_INVOKABLE void start();

    // SpaceCloud: baixa o instalador da atualização pro %TEMP% e já deixa
    // pronto. Chamado pela UI quando o usuário clica em "Atualizar" — assim o
    // clique vira instalação de verdade (Inno Setup silencioso) em vez de só
    // abrir o navegador pra baixar manualmente.
    Q_INVOKABLE void downloadAndInstall(QString url);

signals:
    void onUpdateAvailable(QString newVersion, QString url);

    // Progresso do download (0-100) e resultado. installReady = instalador
    // baixado e executado — o app deve fechar pra instalação continuar.
    void downloadProgress(int percent);
    void downloadFailed(QString error);
    void installReady();

private slots:
    void handleUpdateCheckRequestFinished(QNetworkReply* reply);

private:
    void parseStringToVersionQuad(QString& string, QVector<int>& version);

    int compareVersion(QVector<int>& version1, QVector<int>& version2);

    QString getPlatform();

    QVector<int> m_CurrentVersionQuad;
    QNetworkAccessManager* m_Nam;
    QNetworkReply* m_DownloadReply;
};
