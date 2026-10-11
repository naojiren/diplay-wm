// Only the already-authorized framework usbfs fd is used. No root, reset or configuration ioctl.
#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

static int valid(jint fd, jint id) { return fd >= 0 && id >= 0 && id <= 255; }
static int result(int rc) { return rc < 0 ? errno : 0; }

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_LegacyNcmNative_claim(
        JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (!valid(fd, id)) return EINVAL;
    unsigned int interface_id = (unsigned int) id;
    return result(ioctl(fd, USBDEVFS_CLAIMINTERFACE, &interface_id));
}

JNIEXPORT jstring JNICALL Java_com_shilapi_xcertplay_transport_LegacyNcmNative_driver(
        JNIEnv *env, jobject self, jint fd, jint id) {
    (void) self;
    struct usbdevfs_getdriver query;
    memset(&query, 0, sizeof(query));
    query.interface = (unsigned int) id;
    int err = valid(fd, id) ? result(ioctl(fd, USBDEVFS_GETDRIVER, &query)) : EINVAL;
    query.driver[sizeof(query.driver) - 1] = '\0';
    char value[USBDEVFS_MAXDRIVERNAME + 32];
    snprintf(value, sizeof(value), "%d:%s", err, err == 0 ? query.driver : "");
    return (*env)->NewStringUTF(env, value);
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_LegacyNcmNative_disconnectClaim(
        JNIEnv *env, jobject self, jint fd, jint id, jstring driver) {
    (void) self;
    if (!valid(fd, id) || driver == NULL) return EINVAL;
    const char *name = (*env)->GetStringUTFChars(env, driver, NULL);
    if (name == NULL) return ENOMEM;
    // Defense in depth: never detach another userspace owner, even if Kotlin is called incorrectly.
    if (strcmp(name, "cdc_ncm") != 0 && strcmp(name, "cdc_ether") != 0) {
        (*env)->ReleaseStringUTFChars(env, driver, name);
        return EINVAL;
    }
    struct usbdevfs_disconnect_claim request;
    memset(&request, 0, sizeof(request));
    request.interface = (unsigned int) id;
    request.flags = USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER;
    memcpy(request.driver, name, strlen(name) + 1);
    (*env)->ReleaseStringUTFChars(env, driver, name);
    // One ioctl, one attempt. The driver-name check and detach/claim run under the device lock.
    return result(ioctl(fd, USBDEVFS_DISCONNECT_CLAIM, &request));
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_LegacyNcmNative_reconnectIfUnbound(
        JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (!valid(fd, id)) return EINVAL;
    struct usbdevfs_getdriver query;
    memset(&query, 0, sizeof(query));
    query.interface = (unsigned int) id;
    if (ioctl(fd, USBDEVFS_GETDRIVER, &query) == 0) return 0; // Already bound; leave it alone.
    if (errno != ENODATA) return errno;
    struct usbdevfs_ioctl request;
    memset(&request, 0, sizeof(request));
    request.ifno = id;
    request.ioctl_code = USBDEVFS_CONNECT;
    return result(ioctl(fd, USBDEVFS_IOCTL, &request));
}
