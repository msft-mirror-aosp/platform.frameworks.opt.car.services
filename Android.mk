LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src) \
    src/com/android/internal/car/ICarServiceHelper.aidl \

LOCAL_JAVA_LIBRARIES := services

LOCAL_MODULE := car-frameworks-service

include $(BUILD_JAVA_LIBRARY)

# Include subdirectory makefiles
# ============================================================
include $(call all-makefiles-under,$(LOCAL_PATH))
