#pragma once

// Space Connect: headless "connect --ipc" action (JSON lines over stdin/stdout).
// Licensed under GPL-3.0 like the rest of the project.

#include <QObject>

class ComputerManager;

namespace CliIpcConnect
{

// Reads the connect request from stdin, drives pairing/launch/stream and
// reports progress on stdout. Quits the QCoreApplication when done.
class Launcher;

Launcher* start(QObject* parent);

}
