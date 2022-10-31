LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Multi-User IMMS is guarded by BUILD_AUTOMOTIVE_IMMS_PREBUILT
# Since it should be only used in Android Auto Multi-User builds
ifeq ($(BUILD_AUTOMOTIVE_IMMS_PREBUILT), true)

LOCAL_MODULE := mu_imms
LOCAL_SRC_FILES := $(call all-java-files-under, .)
LOCAL_JAVA_LIBRARIES := services.core.unboosted
include $(BUILD_STATIC_JAVA_LIBRARY)

endif  # BUILD_AUTOMOTIVE_IMMS_PREBUILT
