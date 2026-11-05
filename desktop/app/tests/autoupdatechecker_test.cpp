#include <QtTest>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

#include "backend/autoupdatechecker.h"

class ManifestReply : public QNetworkReply
{
public:
    explicit ManifestReply(const QByteArray& data) : m_Data(data)
    {
        open(QIODevice::ReadOnly);
        setFinished(true);
    }

    void abort() override {}
    qint64 bytesAvailable() const override
    {
        return m_Data.size() - m_Position + QNetworkReply::bytesAvailable();
    }

protected:
    qint64 readData(char* target, qint64 size) override
    {
        const qint64 count = qMin(size, qint64(m_Data.size()) - m_Position);
        if (count == 0) return -1;
        memcpy(target, m_Data.constData() + m_Position, size_t(count));
        m_Position += count;
        return count;
    }

private:
    QByteArray m_Data;
    qint64 m_Position = 0;
};

class AutoUpdateCheckerTest : public QObject
{
    Q_OBJECT

private slots:
    void comparesAgainstTheRunningApplicationRatherThanAStaleObjectFile_data()
    {
        QTest::addColumn<QString>("offeredVersion");
        QTest::addColumn<bool>("updateExpected");
        QTest::newRow("installed-version") << QString("0.3.48") << false;
        QTest::newRow("newer-release") << QString("0.3.49") << true;
        QTest::newRow("older-release") << QString("0.3.47") << false;
    }

    void comparesAgainstTheRunningApplicationRatherThanAStaleObjectFile()
    {
        QFETCH(QString, offeredVersion);
        QFETCH(bool, updateExpected);
        QCoreApplication::setApplicationVersion("0.3.48");
        AutoUpdateChecker checker;
        QSignalSpy offered(&checker, &AutoUpdateChecker::onUpdateAvailable);
#ifdef APP_IMAGE
        const QString platform = QStringLiteral("appimage");
#else
        const QString platform = QSysInfo::productType();
#endif
        const QJsonArray manifest { QJsonObject {
            {"platform", platform},
            {"arch", QSysInfo::buildCpuArchitecture()},
            {"version", offeredVersion},
            {"browser_url", "https://downloads.spacecloud.gg/test-installer.exe"},
        } };
        auto* reply = new ManifestReply(QJsonDocument(manifest).toJson());
        QVERIFY(QMetaObject::invokeMethod(&checker, "handleUpdateCheckRequestFinished",
            Qt::DirectConnection, Q_ARG(QNetworkReply*, reply)));
        QCOMPARE(offered.count(), updateExpected ? 1 : 0);
        if (updateExpected) QCOMPARE(offered.first().at(0).toString(), offeredVersion);
    }
};

QTEST_GUILESS_MAIN(AutoUpdateCheckerTest)
#include "autoupdatechecker_test.moc"
