#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <pty.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#define TAG "VoidPty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jintArray JNICALL
Java_com_hybridengine_terminal_LocalProcessSession_createSubprocessNative(
    JNIEnv *env,
    jclass clazz,
    jstring cmd,
    jobjectArray args,
    jobjectArray envp,
    jstring cwd,
    jint rows,
    jint cols)
{
    int master_fd = -1;
    int slave_fd = -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;

    if (openpty(&master_fd, &slave_fd, NULL, NULL, &ws) < 0) {
        LOGE("openpty failed: %s", strerror(errno));
        return NULL;
    }

    const char *cmd_str = (*env)->GetStringUTFChars(env, cmd, NULL);
    const char *cwd_str = cwd ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;

    int argc = args ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = (char **)malloc((argc + 2) * sizeof(char *));
    argv[0] = strdup(cmd_str);
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *arg_str = (*env)->GetStringUTFChars(env, arg, NULL);
        argv[i + 1] = strdup(arg_str);
        (*env)->ReleaseStringUTFChars(env, arg, arg_str);
    }
    argv[argc + 1] = NULL;

    int envc = envp ? (*env)->GetArrayLength(env, envp) : 0;
    char **environ_child = (char **)malloc((envc + 1) * sizeof(char *));
    for (int i = 0; i < envc; i++) {
        jstring ev = (jstring)(*env)->GetObjectArrayElement(env, envp, i);
        const char *ev_str = (*env)->GetStringUTFChars(env, ev, NULL);
        environ_child[i] = strdup(ev_str);
        (*env)->ReleaseStringUTFChars(env, ev, ev_str);
    }
    environ_child[envc] = NULL;

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(master_fd);
        close(slave_fd);
        for (int i = 0; i <= argc; i++) free(argv[i]);
        free(argv);
        for (int i = 0; i < envc; i++) free(environ_child[i]);
        free(environ_child);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);
        if (cwd_str) (*env)->ReleaseStringUTFChars(env, cwd, cwd_str);
        return NULL;
    }

    if (pid == 0) {
        // Child process
        close(master_fd);

        // Create new session & process group
        setsid();

        // Set controlling terminal
        ioctl(slave_fd, TIOCSCTTY, 0);

        // Redirect stdin, stdout, stderr to slave PTY
        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);
        if (slave_fd > 2) {
            close(slave_fd);
        }

        // Change directory if specified
        if (cwd_str && strlen(cwd_str) > 0) {
            chdir(cwd_str);
        }

        // Execute target binary
        execve(cmd_str, argv, environ_child);

        // If execve fails
        fprintf(stderr, "[VoidPty] execve failed for %s: %s\n", cmd_str, strerror(errno));
        _exit(127);
    }

    // Parent process
    close(slave_fd);

    for (int i = 0; i <= argc; i++) free(argv[i]);
    free(argv);
    for (int i = 0; i < envc; i++) free(environ_child[i]);
    free(environ_child);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);
    if (cwd_str) (*env)->ReleaseStringUTFChars(env, cwd, cwd_str);

    jintArray result = (*env)->NewIntArray(env, 2);
    jint values[2] = { master_fd, pid };
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_hybridengine_terminal_LocalProcessSession_setPtyWindowSizeNative(
    JNIEnv *env,
    jclass clazz,
    jint master_fd,
    jint rows,
    jint cols)
{
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    return ioctl(master_fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_hybridengine_terminal_LocalProcessSession_waitForProcessNative(
    JNIEnv *env,
    jclass clazz,
    jint pid)
{
    int status = 0;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return status;
}

JNIEXPORT jint JNICALL
Java_com_hybridengine_terminal_LocalProcessSession_checkProcessStatusNative(
    JNIEnv *env,
    jclass clazz,
    jint pid)
{
    int status = 0;
    pid_t res = waitpid(pid, &status, WNOHANG);
    if (res == 0) {
        // Still running
        return -999;
    } else if (res == pid) {
        if (WIFEXITED(status)) {
            return WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            return 128 + WTERMSIG(status);
        }
        return status;
    }
    // Process not found or error
    return -1;
}
