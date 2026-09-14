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
