// Preload library for the desktop session (bionic, not glibc despite the folder name): gives
// this app's user the phone's name as display name. Android leaves the passwd "gecos" field
// empty, so menus showed the Android user ID (u0_a123) instead. The login name stays as it is.
// The name comes from POCKET_DEVICE_NAME, set by bin/desktop.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <pwd.h>
#include <stdlib.h>
#include <unistd.h>

static struct passwd *named(struct passwd *pw) {
    const char *name = getenv("POCKET_DEVICE_NAME");
    if (pw && name && *name && pw->pw_uid == getuid()) pw->pw_gecos = (char *) name;
    return pw;
}

struct passwd *getpwuid(uid_t uid) {
    static struct passwd *(*real)(uid_t);
    if (!real) real = dlsym(RTLD_NEXT, "getpwuid");
    return named(real(uid));
}

struct passwd *getpwnam(const char *login) {
    static struct passwd *(*real)(const char *);
    if (!real) real = dlsym(RTLD_NEXT, "getpwnam");
    return named(real(login));
}

int getpwuid_r(uid_t uid, struct passwd *pw, char *buf, size_t len, struct passwd **result) {
    static int (*real)(uid_t, struct passwd *, char *, size_t, struct passwd **);
    if (!real) real = dlsym(RTLD_NEXT, "getpwuid_r");
    int r = real(uid, pw, buf, len, result);
    if (r == 0 && result) named(*result);
    return r;
}

int getpwnam_r(const char *login, struct passwd *pw, char *buf, size_t len, struct passwd **result) {
    static int (*real)(const char *, struct passwd *, char *, size_t, struct passwd **);
    if (!real) real = dlsym(RTLD_NEXT, "getpwnam_r");
    int r = real(login, pw, buf, len, result);
    if (r == 0 && result) named(*result);
    return r;
}
