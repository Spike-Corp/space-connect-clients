# Reuse the release application's objects so this exercises the actual bridge,
# including its native dependencies, without a second full client build.
include(../app.pro)
QT += testlib
CONFIG += testcase console
CONFIG -= app_bundle qtquickcompiler
TARGET = session_orchestrator_test
SOURCES = $$PWD/session_orchestrator_test.cpp
HEADERS =
RESOURCES =
TRANSLATIONS =
INCLUDEPATH += $$PWD/..

APP_OBJECTS = $$files($$OUT_PWD/../app/release/*.o)
isEmpty(APP_OBJECTS): error(Build the release application in the parent directory first)
APP_OBJECTS -= $$OUT_PWD/../app/release/main.o
LIBS = $$APP_OBJECTS $$LIBS
