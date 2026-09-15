cmake_minimum_required(VERSION 3.21)
project(ChunkDaddy VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if (NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

option(CHUNKDADDY_BUILD_TESTS "Build ChunkDaddy unit tests" ON)
option(CHUNKDADDY_BUILD_GUI "Build the Qt desktop application" ON)
option(CHUNKDADDY_BUILD_WORKER "Invoke the Gradle build for the Java worker" OFF)

if (MSVC)
    add_compile_options(/W4 /permissive- /utf-8 /Zc:__cplusplus)
    add_compile_definitions(NOMINMAX WIN32_LEAN_AND_MEAN _CRT_SECURE_NO_WARNINGS)
else()
    add_compile_options(-Wall -Wextra -Wpedantic -Wshadow)
endif()

# ---------------------------------------------------------------------------
# chunkdaddy_core: pure logic, no Qt GUI dependency beyond QtCore.
# Everything here is unit-testable headlessly.
# ---------------------------------------------------------------------------
find_package(Qt6 6.4 REQUIRED COMPONENTS Core)

add_library(chunkdaddy_core STATIC
    src/layout/Geometry.cpp
    src/layout/GridPlanner.cpp
    src/layout/InstanceNaming.cpp
    src/document/Selection.cpp
    src/document/Document.cpp
    src/document/Clipboard.cpp
    src/document/History.cpp
    src/document/Workspace.cpp
    src/worker/Protocol.cpp
    src/worker/WorkerClient.cpp
)
target_include_directories(chunkdaddy_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(chunkdaddy_core PUBLIC Qt6::Core)
set_target_properties(chunkdaddy_core PROPERTIES AUTOMOC ON)

# ---------------------------------------------------------------------------
# chunkdaddy: the desktop application.
# ---------------------------------------------------------------------------
if (CHUNKDADDY_BUILD_GUI)
    find_package(Qt6 REQUIRED COMPONENTS Widgets Gui)
    qt_standard_project_setup()

    qt_add_executable(chunkdaddy
        src/app/main.cpp
        src/app/MainWindow.cpp
        src/app/Settings.cpp
        src/app/NewWorldDialog.cpp
        src/app/OpenWorldRootDialog.cpp
        src/app/SchematicImportDialog.cpp
        src/app/ImportProgressDialog.cpp
        src/app/OperationProgressDialog.cpp
        src/app/SpawnMarkerDialog.cpp
        src/app/ExportDialog.cpp
        src/app/ReportPanel.cpp
        src/app/InspectorPanel.cpp
        src/app/WorldSettingsPanel.cpp
        src/app/TemplatePanel.cpp
        src/view/ChunkView.cpp
        src/view/TileCache.cpp
    )
    target_link_libraries(chunkdaddy PRIVATE chunkdaddy_core Qt6::Widgets Qt6::Gui)
    set_target_properties(chunkdaddy PROPERTIES
        WIN32_EXECUTABLE ON
        MACOSX_BUNDLE OFF
    )

    install(TARGETS chunkdaddy RUNTIME DESTINATION bin)
endif()

# ---------------------------------------------------------------------------
# Java worker (optional: normally built via ./gradlew in worker/).
# ---------------------------------------------------------------------------
if (CHUNKDADDY_BUILD_WORKER)
    if (WIN32)
        set(CHUNKDADDY_GRADLE "${CMAKE_CURRENT_SOURCE_DIR}/worker/gradlew.bat")
    else()
        set(CHUNKDADDY_GRADLE "${CMAKE_CURRENT_SOURCE_DIR}/worker/gradlew")
    endif()
    add_custom_target(chunkdaddy_worker ALL
        COMMAND "${CHUNKDADDY_GRADLE}" --project-dir "${CMAKE_CURRENT_SOURCE_DIR}/worker" installWorker
        COMMENT "Building the ChunkDaddy Java worker"
        VERBATIM
    )
    if (CHUNKDADDY_BUILD_GUI)
        add_dependencies(chunkdaddy chunkdaddy_worker)
    endif()
endif()

if (CHUNKDADDY_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
