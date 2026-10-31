# Reuse the release application's objects so this exercises the actual bridge,
# including its native dependencies, without a second full client build.
include(../app.pro)
QT += testlib
CONFIG += testcase console
CONFIG -= app_bundle qtquickcompiler
DEFINES += SDL_MAIN_HANDLED
TARGET = session_orchestrator_test
SOURCES = $$PWD/session_orchestrator_test.cpp
HEADERS =
RESOURCES =
TRANSLATIONS =
RC_ICONS =
INCLUDEPATH += $$PWD/..

APP_OBJECTS = $$files($$OUT_PWD/../app/release/*.o)
win32: APP_OBJECTS = $$files($$OUT_PWD/../app/release/*.obj)
isEmpty(APP_OBJECTS): error(Build the release application in the parent directory first)
APP_OBJECTS -= $$OUT_PWD/../app/release/main.o
APP_OBJECTS -= $$OUT_PWD/../app/release/main.obj
LIBS = $$APP_OBJECTS $$LIBS
