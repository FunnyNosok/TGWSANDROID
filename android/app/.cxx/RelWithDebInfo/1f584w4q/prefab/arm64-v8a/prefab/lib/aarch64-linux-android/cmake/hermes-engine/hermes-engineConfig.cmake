if(NOT TARGET hermes-engine::libhermes)
add_library(hermes-engine::libhermes SHARED IMPORTED)
set_target_properties(hermes-engine::libhermes PROPERTIES
    IMPORTED_LOCATION "C:/Users/danil/.gradle/caches/8.10.2/transforms/d87493c3d0dc3b3aa0704545aeb26632/transformed/jetified-hermes-android-0.78.2-release/prefab/modules/libhermes/libs/android.arm64-v8a/libhermes.so"
    INTERFACE_INCLUDE_DIRECTORIES "C:/Users/danil/.gradle/caches/8.10.2/transforms/d87493c3d0dc3b3aa0704545aeb26632/transformed/jetified-hermes-android-0.78.2-release/prefab/modules/libhermes/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

