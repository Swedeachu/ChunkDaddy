# Focused unit tests for the logic that is easy to get subtly wrong: floor division on
# negative coordinates, checked extents, grid arithmetic, naming rules and selection
# membership. Format and storage behaviour is tested on the worker side, against small
# owned fixtures rather than by regenerating 450 arenas.
find_package(Qt6 REQUIRED COMPONENTS Core Test)

set(CHUNKDADDY_TESTS
    test_geometry
    test_gridplanner
    test_instancenaming
    test_selection
    test_protocol
)

foreach(test_name IN LISTS CHUNKDADDY_TESTS)
    qt_add_executable(${test_name} ${test_name}.cpp)
    target_link_libraries(${test_name} PRIVATE chunkdaddy_core Qt6::Test)
    set_target_properties(${test_name} PROPERTIES AUTOMOC ON WIN32_EXECUTABLE OFF)
    add_test(NAME ${test_name} COMMAND ${test_name})
endforeach()

# This integration check requires the staged runtime; setup invokes it after jlink.
qt_add_executable(test_workerclient test_workerclient.cpp)
target_link_libraries(test_workerclient PRIVATE chunkdaddy_core Qt6::Test)
set_target_properties(test_workerclient PROPERTIES AUTOMOC ON WIN32_EXECUTABLE OFF
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")

if (CHUNKDADDY_BUILD_GUI)
    qt_add_executable(test_viewpreview test_viewpreview.cpp ../src/view/ChunkView.cpp ../src/view/TileCache.cpp)
    target_link_libraries(test_viewpreview PRIVATE chunkdaddy_core Qt6::Widgets Qt6::Test)
    set_target_properties(test_viewpreview PROPERTIES AUTOMOC ON WIN32_EXECUTABLE OFF)
    add_test(NAME test_viewpreview COMMAND test_viewpreview)
    set_tests_properties(test_viewpreview PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen")
    if (TARGET Qt6::QOffscreenIntegrationPlugin)
        set_property(TEST test_viewpreview APPEND PROPERTY ENVIRONMENT
            "QT_QPA_PLATFORM_PLUGIN_PATH=$<TARGET_FILE_DIR:Qt6::QOffscreenIntegrationPlugin>")
    endif()
    qt_add_executable(test_importdialogs test_importdialogs.cpp
        ../src/app/ImportProgressDialog.cpp ../src/app/OperationProgressDialog.cpp
        ../src/app/SchematicImportDialog.cpp ../src/app/Settings.cpp)
    target_link_libraries(test_importdialogs PRIVATE chunkdaddy_core Qt6::Widgets Qt6::Test)
    set_target_properties(test_importdialogs PROPERTIES AUTOMOC ON WIN32_EXECUTABLE OFF)
    add_test(NAME test_importdialogs COMMAND test_importdialogs)
    set_tests_properties(test_importdialogs PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen")
    if (TARGET Qt6::QOffscreenIntegrationPlugin)
        set_property(TEST test_importdialogs APPEND PROPERTY ENVIRONMENT
            "QT_QPA_PLATFORM_PLUGIN_PATH=$<TARGET_FILE_DIR:Qt6::QOffscreenIntegrationPlugin>")
    endif()
endif()
