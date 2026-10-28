#include <QtTest>

#include "../backend/launcherbitrate.h"

using namespace LauncherBitrate;

namespace {
QVariantMap machine(const QString& id, const QString& name, const QString& tier, int maxKbps, int recommendedKbps)
{
    QVariantMap map;
    map.insert(QStringLiteral("id"), id);
    map.insert(QStringLiteral("name"), name);
    map.insert(QStringLiteral("planName"), QStringLiteral("Plano %1").arg(name));
    map.insert(QStringLiteral("bitrateTier"), tier);
    map.insert(QStringLiteral("maxBitrateKbps"), maxKbps);
    map.insert(QStringLiteral("recommendedBitrateKbps"), recommendedKbps);
    return map;
}

QVariantMap physical(const QString& id)
{
    return machine(id, QStringLiteral("PC-FISICO"), QStringLiteral("physical"), 100000, 50000);
}

QVariantMap cloud(const QString& id)
{
    return machine(id, QStringLiteral("PC-CLOUD"), QStringLiteral("cloud"), 25000, 25000);
}

// Backend antigo: a VM chega sem tipo/teto de bitrate.
QVariantMap legacy(const QString& id)
{
    QVariantMap map;
    map.insert(QStringLiteral("id"), id);
    map.insert(QStringLiteral("name"), QStringLiteral("PC-ANTIGO"));
    return map;
}
}

class LauncherBitrateTest : public QObject
{
    Q_OBJECT

private slots:
    void dedicatedMachineUnlocksHundredMbpsBeforeAnyConnection()
    {
        const Detection detection = detect({physical(QStringLiteral("m1"))}, QStringLiteral("m1"), 0, 0);

        QCOMPARE(detection.tier, QStringLiteral("physical"));
        QCOMPARE(detection.source, QStringLiteral("machine"));
        QCOMPARE(detection.machineName, QStringLiteral("PC-FISICO"));
        QCOMPARE(effectiveMaxKbps(detection, ProfileAuto), 100000);
        QCOMPARE(effectiveRecommendedKbps(detection, ProfileAuto), 50000);
    }

    void cloudMachineStaysAtTwentyFiveMbps()
    {
        const Detection detection = detect({cloud(QStringLiteral("m1"))}, QStringLiteral("m1"), 0, 0);

        QCOMPARE(detection.tier, QStringLiteral("cloud"));
        QCOMPARE(effectiveMaxKbps(detection, ProfileAuto), 25000);
        QCOMPARE(effectiveRecommendedKbps(detection, ProfileAuto), 25000);
    }

    void mixedAccountFollowsTheSelectedMachine()
    {
        const QVariantList machines = {physical(QStringLiteral("m1")), cloud(QStringLiteral("m2"))};

        QCOMPARE(effectiveMaxKbps(detect(machines, QStringLiteral("m1"), 0, 0), ProfileAuto), 100000);
        QCOMPARE(effectiveMaxKbps(detect(machines, QStringLiteral("m2"), 0, 0), ProfileAuto), 25000);
    }

    void mixedAccountWithoutSelectionDoesNotGuessFromMachines()
    {
        const QVariantList machines = {physical(QStringLiteral("m1")), cloud(QStringLiteral("m2"))};

        const Detection nothing = detect(machines, QString(), 0, 0);
        QCOMPARE(nothing.source, QString());
        QCOMPARE(effectiveMaxKbps(nothing, ProfileAuto), 0);

        const Detection cached = detect(machines, QString(), 50000, 25000);
        QCOMPARE(cached.source, QStringLiteral("cache"));
        QCOMPARE(effectiveMaxKbps(cached, ProfileAuto), 50000);
    }

    void sameTypeMachinesWithoutSelectionAreDetected()
    {
        const Detection two = detect({physical(QStringLiteral("m1")), physical(QStringLiteral("m2"))}, QString(), 0, 0);
        QCOMPARE(two.source, QStringLiteral("machines"));
        QCOMPARE(two.tier, QStringLiteral("physical"));
        QCOMPARE(two.machineName, QString());
        QCOMPARE(effectiveMaxKbps(two, ProfileAuto), 100000);

        const Detection one = detect({cloud(QStringLiteral("m1"))}, QString(), 0, 0);
        QCOMPARE(one.source, QStringLiteral("machines"));
        QCOMPARE(one.machineName, QStringLiteral("PC-CLOUD"));
    }

    void oldBackendFallsBackToTheCachedCeiling()
    {
        const Detection detection = detect({legacy(QStringLiteral("m1"))}, QStringLiteral("m1"), 100000, 50000);

        QCOMPARE(detection.source, QStringLiteral("cache"));
        QCOMPARE(detection.tier, QString());
        QCOMPARE(effectiveMaxKbps(detection, ProfileAuto), 100000);
        QCOMPARE(effectiveRecommendedKbps(detection, ProfileAuto), 50000);
    }

    void nothingKnownKeepsTheLocalHeuristic()
    {
        const Detection detection = detect({legacy(QStringLiteral("m1"))}, QStringLiteral("m1"), 0, 0);

        QCOMPARE(detection.source, QString());
        QCOMPARE(effectiveMaxKbps(detection, ProfileAuto), 0);
    }

    void manualProfileSetsTheCeilingWhenNothingIsDetected()
    {
        const Detection detection = detect({}, QString(), 0, 0);

        QCOMPARE(effectiveMaxKbps(detection, ProfilePhysical), 100000);
        QCOMPARE(effectiveRecommendedKbps(detection, ProfilePhysical), 50000);
        QCOMPARE(effectiveMaxKbps(detection, ProfileCloud), 25000);
        QCOMPARE(effectiveRecommendedKbps(detection, ProfileCloud), 25000);
    }

    void manualDedicatedProfileOverridesAStaleCachedCeiling()
    {
        // O cache é global: pode ser da VM cloud de outra conexão. Sem tipo vindo do backend,
        // quem escolheu "Host dedicado" à mão precisa conseguir passar de 25 Mbps.
        const Detection detection = detect({legacy(QStringLiteral("m1"))}, QStringLiteral("m1"), 25000, 25000);

        QCOMPARE(effectiveMaxKbps(detection, ProfilePhysical), 100000);
    }

    void manualDedicatedProfileCannotRaiseAKnownCloudMachine()
    {
        const Detection detection = detect({cloud(QStringLiteral("m1"))}, QStringLiteral("m1"), 0, 0);

        QCOMPARE(effectiveMaxKbps(detection, ProfilePhysical), 25000);
        QCOMPARE(effectiveRecommendedKbps(detection, ProfilePhysical), 25000);
    }

    void manualCloudProfileCapsADedicatedMachine()
    {
        const Detection detection = detect({physical(QStringLiteral("m1"))}, QStringLiteral("m1"), 0, 0);

        QCOMPARE(effectiveMaxKbps(detection, ProfileCloud), 25000);
        QCOMPARE(effectiveRecommendedKbps(detection, ProfileCloud), 25000);
    }

    void invalidProfileFallsBackToAutomatic()
    {
        QCOMPARE(normalizeProfile(-1), int(ProfileAuto));
        QCOMPARE(normalizeProfile(3), int(ProfileAuto));
        QCOMPARE(normalizeProfile(1), int(ProfilePhysical));
        QCOMPARE(normalizeProfile(2), int(ProfileCloud));
    }
};

QTEST_APPLESS_MAIN(LauncherBitrateTest)
#include "launcherbitrate_test.moc"
