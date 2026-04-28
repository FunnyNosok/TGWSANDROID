if(NOT TARGET hermes-engine::libhermes)
add_library(hermes-engine::libhermes SHARED IMPORTED)
set_target_properties(hermes-engine::libhermes PROPERTIES
    IMPORTED_LOCATION "C:/Users/danil/.gradle/caches/8.10.2/transforms/d8285b0758734ab2a7845bc121fbbd8d/transformed/jetified-hermes-android-0.78.2-debug/prefab/modules/libhermes/libs/android.armeabi-v7a/libhermes.so"
    INTERFACE_INCLUDE_DIRECTORIES "C:/Users/danil/.gradle/caches/8.10.2/transforms/d8285b0758734ab2a7845bc121fbbd8d/transformed/jetified-hermes-android-0.78.2-debug/prefab/modules/libhermes/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

