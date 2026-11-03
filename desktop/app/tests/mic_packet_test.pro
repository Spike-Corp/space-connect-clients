QT += core testlib
CONFIG += console testcase c++17
CONFIG -= app_bundle
TARGET = mic_packet_test
unix {
    CONFIG += link_pkgconfig
    PKGCONFIG += openssl
}
win32 {
    INCLUDEPATH += $$PWD/../../libs/windows/include $$PWD/../../libs/windows/include/x64
    LIBS += -L$$PWD/../../libs/windows/lib/x64 -llibcrypto
}
SOURCES += mic_packet_test.cpp ../backend/micpacket.cpp
HEADERS += ../backend/micpacket.h
