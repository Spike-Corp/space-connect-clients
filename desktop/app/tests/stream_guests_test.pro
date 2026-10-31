QT += core gui network testlib
CONFIG += console testcase c++17
CONFIG -= app_bundle
TARGET = stream_guests_test
INCLUDEPATH += $$PWD/..
unix {
    CONFIG += link_pkgconfig
    PKGCONFIG += openssl
}
win32 {
    INCLUDEPATH += $$PWD/../../libs/windows/include $$PWD/../../libs/windows/include/x64
    LIBS += -L$$PWD/../../libs/windows/lib/x64 -llibcrypto -llibssl -lwinmm
}
SOURCES += stream_guests_test.cpp \
    ../backend/launcherapi.cpp \
    ../backend/launcherjson.cpp \
    ../backend/recaptchafetcher.cpp \
    ../backend/identitymanager.cpp \
    ../backend/streamguestcontroller.cpp
HEADERS += ../backend/launcherapi.h ../backend/recaptchafetcher.h ../backend/streamguestcontroller.h
