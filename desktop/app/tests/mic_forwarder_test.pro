QT += core network testlib
CONFIG += console testcase c++17
CONFIG -= app_bundle
TARGET = mic_forwarder_test
unix {
    CONFIG += link_pkgconfig
    PKGCONFIG += openssl sdl2
}
win32 {
    INCLUDEPATH += $$PWD/../../libs/windows/include $$PWD/../../libs/windows/include/x64
    LIBS += -L$$PWD/../../libs/windows/lib/x64 -llibcrypto -lSDL2
}
SOURCES += mic_forwarder_test.cpp ../backend/micforwarder.cpp ../backend/micpacket.cpp
HEADERS += ../backend/micforwarder.h ../backend/micpacket.h
