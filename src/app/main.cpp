#include "app/MainWindow.h"

#include <QApplication>

int main(int argc, char** argv) {
    QApplication app(argc, argv);
    QApplication::setApplicationName(QStringLiteral("ChunkDaddy"));
    QApplication::setOrganizationName(QStringLiteral("SaiCo"));
    QApplication::setApplicationVersion(QStringLiteral("0.1.0"));

    chunkdaddy::MainWindow window;
    window.show();
    // The worker is started after the window is visible so a failure to launch it is
    // reported in the application rather than on a console the user never sees.
    window.initialise();

    return QApplication::exec();
}
