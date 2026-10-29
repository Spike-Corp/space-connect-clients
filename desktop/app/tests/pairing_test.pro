QT += core gui network testlib
CONFIG += console testcase c++17 link_pkgconfig
CONFIG -= app_bundle
TARGET = pairing_test
PKGCONFIG += openssl

isEmpty(MOONLIGHT_LIB_DIR): MOONLIGHT_LIB_DIR = $$PWD/../../build/build-release/moonlight-common-c
LIBS += -L$$MOONLIGHT_LIB_DIR -lmoonlight-common-c -lpthread
INCLUDEPATH += $$PWD/.. $$PWD/../../moonlight-common-c/moonlight-common-c/src
SOURCES += \
    pairing_test.cpp \
    ../backend/identitymanager.cpp \
    ../backend/launcherjson.cpp \
    ../backend/nvaddress.cpp \
    ../backend/nvapp.cpp \
    ../backend/nvhttp.cpp \
    ../backend/nvpairingmanager.cpp
HEADERS += ../backend/nvhttp.h
