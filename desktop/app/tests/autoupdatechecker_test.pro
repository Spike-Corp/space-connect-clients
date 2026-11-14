QT += core network testlib
QT -= gui
CONFIG += console testcase c++17
CONFIG -= app_bundle
TARGET = autoupdatechecker_test
INCLUDEPATH += ..
DEFINES += VERSION_STR=\\\"0.3.47\\\"
SOURCES += autoupdatechecker_test.cpp ../backend/autoupdatechecker.cpp
HEADERS += ../backend/autoupdatechecker.h
