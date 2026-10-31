QT += core testlib
QT -= gui
CONFIG += console testcase c++17
CONFIG -= app_bundle
DEFINES += SDL_MAIN_HANDLED
TARGET = stream_menu_test
unix {
    CONFIG += link_pkgconfig
    PKGCONFIG += sdl2
}
win32 {
    INCLUDEPATH += $$PWD/../../libs/windows/include $$PWD/../../libs/windows/include/x64
}
SOURCES += stream_menu_test.cpp ../streaming/streammenustate.cpp
