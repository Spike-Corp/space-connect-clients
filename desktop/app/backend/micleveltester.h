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
    Q_INVOKABLE bool isTesting() { emit testingChanged(); return m_Running.load(); }

signals:
    // Passa o nível junto (0.0-1.0) — o QML atualiza a barra direto do sinal,
    // sem depender de uma segunda leitura do singleton (que podia não
    // re-renderizar a tempo e a barra parecia travada).
    void levelChanged(float level);
    // Sinal sem parâmetro, pro QML que só quer saber se ligou/desligou.
    void testingChanged();

protected:
    void run() override;

private:
    QString m_CaptureDeviceName;
    std::atomic<bool> m_Running;
    std::atomic<float> m_CurrentLevel;
};
