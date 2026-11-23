#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TAG "KalinDroidPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static char **to_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) return NULL;
    jsize count = (*env)->GetArrayLength(env, array);
    char **out = (char **)calloc((size_t)count + 1, sizeof(char *));
    if (out == NULL) return NULL;
    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        if (item == NULL) continue;
        const char *utf = (*env)->GetStringUTFChars(env, item, NULL);
        if (utf != NULL) {
            out[i] = strdup(utf);
            (*env)->ReleaseStringUTFChars(env, item, utf);
        }
        (*env)->DeleteLocalRef(env, item);
    }
    return out;
}

static void *reap_child(void *arg) {
    pid_t pid = (pid_t)(intptr_t)arg;
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
    }
    return NULL;
}

static void free_array(char **array) {
    if (array == NULL) return;
    for (size_t i = 0; array[i] != NULL; i++) free(array[i]);
    free(array);
}

JNIEXPORT jint JNICALL
Java_com_kalindroid_app_Pty_nativeOpen(JNIEnv *env, jclass clazz,
                                       jobjectArray argv, jobjectArray envp,
                                       jstring cwd, jint cols, jint rows) {
    (void)clazz;
    char **cargv = to_array(env, argv);
    char **cenvp = to_array(env, envp);
    if (cargv == NULL || cargv[0] == NULL) {
        free_array(cargv);
        free_array(cenvp);
        return -1;
    }

    const char *ccwd = NULL;
    if (cwd != NULL) ccwd = (*env)->GetStringUTFChars(env, cwd, NULL);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        if (ccwd != NULL) (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
        free_array(cargv);
        free_array(cenvp);
        return -1;
    }

    if (pid == 0) {
        if (ccwd != NULL) chdir(ccwd);
        execve(cargv[0], cargv, cenvp);
        _exit(127);
    }

    pthread_t reaper;
    if (pthread_create(&reaper, NULL, reap_child, (void *)(intptr_t)pid) == 0) {
        pthread_detach(reaper);
    }

    if (ccwd != NULL) (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
    free_array(cargv);
    free_array(cenvp);

    fcntl(master, F_SETFD, FD_CLOEXEC);
    return (jint)master;
}

JNIEXPORT jint JNICALL
Java_com_kalindroid_app_Pty_nativeRead(JNIEnv *env, jclass clazz,
                                       jint fd, jbyteArray buffer,
                                       jint offset, jint length) {
    (void)clazz;
    if (length <= 0) return 0;
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) return -1;
    ssize_t n;
    do {
        n = read((int)fd, bytes + offset, (size_t)length);
    } while (n < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, 0);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_kalindroid_app_Pty_nativeWrite(JNIEnv *env, jclass clazz,
                                        jint fd, jbyteArray buffer,
                                        jint offset, jint length) {
    (void)clazz;
    if (length <= 0) return 0;
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) return -1;
    ssize_t total = 0;
    while (total < length) {
        ssize_t n = write((int)fd, bytes + offset + total, (size_t)(length - total));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        total += n;
    }
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    return (jint)total;
}

JNIEXPORT void JNICALL
Java_com_kalindroid_app_Pty_nativeResize(JNIEnv *env, jclass clazz,
                                         jint fd, jint cols, jint rows) {
    (void)env;
    (void)clazz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ioctl((int)fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_com_kalindroid_app_Pty_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    (void)env;
    (void)clazz;
    if (fd >= 0) close((int)fd);
}
