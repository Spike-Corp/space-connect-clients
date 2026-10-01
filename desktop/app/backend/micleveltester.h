#pragma once

#include <QObject>
#include <QThread>
#include <atomic>

// Medidor de nível do microfone estilo Discord: captura local (sem enviar
// nada pra lugar nenhum) só pra mostrar a barrinha de sensibilidade nas
// configurações. O usuário vê na hora se o mic certo está captando, sem
// precisar entrar numa sessão pra descobrir que o mic estava mudo/errado.
class MicLevelTester : public QThread
{
    Q_OBJECT

public:
    explicit MicLevelTester(QObject* parent = nullptr);
    ~MicLevelTester() override;

    // captureDeviceName = nome SDL do dispositivo (vazio = padrão do sistema)
    Q_INVOKABLE void startTesting(const QString& captureDeviceName = QString());
    Q_INVOKABLE void stopTesting();

    // 0.0 (silêncio) a 1.0 (alto) — mesma curva RMS do MicForwarder pra o
    // medidor das configurações bater com o que a VM vai receber.
    Q_INVOKABLE float currentLevel() const { return m_CurrentLevel.load(); }
    Q_INVOKABLE bool isTesting() const { return m_Running.load(); }

signals:
    void levelChanged();

protected:
    void run() override;

private:
    QString m_CaptureDeviceName;
    std::atomic<bool> m_Running;
    std::atomic<float> m_CurrentLevel;
};
